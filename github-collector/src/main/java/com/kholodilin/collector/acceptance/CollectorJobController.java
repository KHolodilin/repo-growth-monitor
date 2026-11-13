package com.kholodilin.collector.acceptance;

import com.kholodilin.collector.contracts.CollectorProtocol.*;
import com.kholodilin.collector.queue.AttemptConflictException;
import com.kholodilin.idempotency.exception.IdempotencyConflictException;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import java.util.Map;

@RestController
@RequestMapping("/internal/v1/jobs")
public final class CollectorJobController {
    private final CollectorAcceptanceService service;
    public CollectorJobController(CollectorAcceptanceService service) {this.service=service;}
    @PostMapping
    public ResponseEntity<JobAccepted> accept(@RequestHeader("Idempotency-Key") String key,@RequestBody JobRequest request,Authentication authentication) {
        return ResponseEntity.status(201).body(service.accept(authentication.getName(),key,request));
    }
    @ExceptionHandler({IdempotencyConflictException.class,AttemptConflictException.class})
    ResponseEntity<Map<String,String>> conflict(Exception error) {
        return ResponseEntity.status(409).body(Map.of("code","IDEMPOTENCY_CONFLICT","message","Key or attempt was used for different content"));
    }
    @ExceptionHandler({IllegalArgumentException.class,HttpMessageNotReadableException.class,org.springframework.web.bind.MissingRequestHeaderException.class})
    ResponseEntity<Map<String,String>> invalid(Exception error) {
        return ResponseEntity.badRequest().body(Map.of("code","INVALID_REQUEST","message","Invalid job envelope or Idempotency-Key"));
    }
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    ResponseEntity<Map<String,String>> unavailable(Exception error) {
        return ResponseEntity.status(503).body(Map.of("code","STORAGE_UNAVAILABLE","message","Durable acceptance unavailable"));
    }
}
