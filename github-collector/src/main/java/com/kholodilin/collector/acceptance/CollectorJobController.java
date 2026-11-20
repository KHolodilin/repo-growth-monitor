package com.kholodilin.collector.acceptance;

import com.kholodilin.collector.CollectorObservabilityConfiguration;
import com.kholodilin.collector.contracts.CollectorProtocol.*;
import com.kholodilin.collector.queue.AttemptConflictException;
import com.kholodilin.collector.queue.CapacityExceededException;
import com.kholodilin.idempotency.exception.IdempotencyConflictException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/v1/jobs")
public class CollectorJobController {
  private final CollectorAcceptanceService service;

  @PostMapping
  public ResponseEntity<JobAccepted> accept(
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody JobRequest request,
      Authentication authentication) {
    JobAccepted body = service.accept(authentication.getName(), key, request);
    CollectorObservabilityConfiguration.ACCEPTED.incrementAndGet();
    log.info(
        "Accepted collector job type={} collectorJobId={}", request.type(), body.collectorJobId());
    return ResponseEntity.status(201).body(body);
  }

  @ExceptionHandler(CapacityExceededException.class)
  ResponseEntity<Map<String, String>> capacity(CapacityExceededException error) {
    CollectorObservabilityConfiguration.REJECTED_CAPACITY.incrementAndGet();
    log.info("Rejected job for capacity/shutdown: {}", error.getMessage());
    return ResponseEntity.status(503)
        .header(HttpHeaders.RETRY_AFTER, "30")
        .body(
            Map.of(
                "code",
                "CAPACITY",
                "message",
                error.getMessage() == null ? "Collector capacity exceeded" : error.getMessage()));
  }

  @ExceptionHandler({IdempotencyConflictException.class, AttemptConflictException.class})
  ResponseEntity<Map<String, String>> conflict(Exception error) {
    log.info("Idempotency conflict on accept: {}", error.getMessage());
    return ResponseEntity.status(409)
        .body(
            Map.of(
                "code", "IDEMPOTENCY_CONFLICT",
                "message", "Key or attempt was used for different content"));
  }

  @ExceptionHandler({
    IllegalArgumentException.class,
    HttpMessageNotReadableException.class,
    org.springframework.web.bind.MissingRequestHeaderException.class
  })
  ResponseEntity<Map<String, String>> invalid(Exception error) {
    log.debug("Invalid accept envelope: {}", error.toString());
    return ResponseEntity.badRequest()
        .body(
            Map.of(
                "code", "INVALID_REQUEST",
                "message", "Invalid job envelope or Idempotency-Key"));
  }
}
