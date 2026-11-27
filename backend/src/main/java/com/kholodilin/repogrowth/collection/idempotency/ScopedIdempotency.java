package com.kholodilin.repogrowth.collection.idempotency;

import com.kholodilin.idempotency.jackson.CanonicalJsonFingerprintStrategy;
import java.util.List;

/**
 * Key derivation only. Acquisition, fingerprinting, replay and conflicts belong to the published
 * starter. Byte-compatible with the collector's scoped-key helper so existing records keep
 * matching.
 */
public final class ScopedIdempotency {
  private ScopedIdempotency() {}

  public static String key(String trustedScope, String key) {
    if (trustedScope == null || trustedScope.isBlank())
      throw new IllegalArgumentException("Trusted scope is required");
    if (key == null || !key.matches("[A-Za-z0-9._:-]{1,128}"))
      throw new InvalidIdempotencyKeyException();
    return new CanonicalJsonFingerprintStrategy().calculate(List.of(trustedScope, key));
  }
}
