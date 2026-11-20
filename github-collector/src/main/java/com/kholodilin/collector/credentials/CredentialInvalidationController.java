package com.kholodilin.collector.credentials;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/** Uses the RGM-origin bearer chain installed by Collector HTTP configuration. */
@RestController
@RequiredArgsConstructor
public class CredentialInvalidationController {
  public record Invalidation(String context) {}

  private final CredentialCache cache;

  @PostMapping("/internal/v1/credentials/invalidate")
  public ResponseEntity<Void> invalidate(
      @RequestBody Invalidation request, Authentication authentication) {
    if (request.context() == null
        || request.context().isBlank()
        || request.context().length() > 128) return ResponseEntity.badRequest().build();
    cache.invalidateContext(authentication.getName(), request.context());
    return ResponseEntity.noContent().build();
  }
}
