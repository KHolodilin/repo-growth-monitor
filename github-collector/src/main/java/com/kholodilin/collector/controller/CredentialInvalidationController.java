package com.kholodilin.collector.controller;

import com.kholodilin.collector.service.CredentialInvalidationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Uses the RGM-origin bearer chain installed by Collector HTTP configuration. */
@RestController
@RequiredArgsConstructor
public class CredentialInvalidationController {
  public record Invalidation(String context) {}

  private final CredentialInvalidationService invalidation;

  @PostMapping("/internal/v1/credentials/invalidate")
  public ResponseEntity<Void> invalidate(
      @RequestBody Invalidation request, Authentication authentication) {
    if (request.context() == null
        || request.context().isBlank()
        || request.context().length() > 128) return ResponseEntity.badRequest().build();
    invalidation.invalidate(authentication.getName(), request.context());
    return ResponseEntity.noContent().build();
  }
}
