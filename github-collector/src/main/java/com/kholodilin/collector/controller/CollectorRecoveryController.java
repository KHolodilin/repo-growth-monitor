package com.kholodilin.collector.controller;

import com.kholodilin.collector.service.RecoveryService;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequiredArgsConstructor
public final class CollectorRecoveryController {
  public record Confirmation(UUID eventId) {}

  private final RecoveryService recovery;
  private final JsonMapper json;

  @GetMapping("/internal/v1/status")
  public Map<String, Object> status(Authentication auth) {
    return recovery.nodeStatus(auth.getName());
  }

  @GetMapping("/internal/v1/jobs/by-attempt/{id}")
  public ResponseEntity<?> byAttempt(@PathVariable UUID id, Authentication auth) {
    return recovery
        .statusByAttempt(id, auth.getName())
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  @GetMapping("/internal/v1/jobs/{id}")
  public ResponseEntity<?> job(@PathVariable UUID id, Authentication auth) {
    return recovery
        .status(id, auth.getName())
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  @GetMapping("/internal/v1/jobs/{id}/result")
  public ResponseEntity<?> result(@PathVariable UUID id, Authentication auth) {
    return recovery
        .result(id, auth.getName())
        .map(value ->
            ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(json.readTree(value)))
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  @PostMapping("/internal/v1/jobs/{id}/delivery/ack")
  public ResponseEntity<Void> confirm(
      @PathVariable UUID id, @RequestBody Confirmation request, Authentication auth) {
    return recovery.confirmDelivery(id, auth.getName(), request.eventId())
        ? ResponseEntity.noContent().build()
        : ResponseEntity.notFound().build();
  }

  @PostMapping("/internal/v1/jobs/{id}/delivery/retry")
  public ResponseEntity<Void> retry(@PathVariable UUID id, Authentication auth) {
    return recovery.requeueDelivery(id, auth.getName())
        ? ResponseEntity.noContent().build()
        : ResponseEntity.notFound().build();
  }
}
