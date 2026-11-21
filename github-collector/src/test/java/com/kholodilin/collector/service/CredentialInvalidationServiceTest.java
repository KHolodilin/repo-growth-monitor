package com.kholodilin.collector.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.kholodilin.collector.integration.credentials.CredentialCache;
import org.junit.jupiter.api.Test;

class CredentialInvalidationServiceTest {
  @Test
  void invalidatesTheContextForTheAuthenticatedOriginOnly() {
    var cache = mock(CredentialCache.class);

    new CredentialInvalidationService(cache).invalidate("rgm", "context-1");

    verify(cache).invalidateContext("rgm", "context-1");
  }
}
