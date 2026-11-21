package com.kholodilin.collector.service;

import com.kholodilin.collector.integration.credentials.CredentialCache;
import lombok.RequiredArgsConstructor;

/** Explicit, origin-scoped revocation of an authorization context. */
@RequiredArgsConstructor
public final class CredentialInvalidationService {
  private final CredentialCache cache;

  /** The origin is the authenticated service identity, never a request field. */
  public void invalidate(String origin, String context) {
    cache.invalidateContext(origin, context);
  }
}
