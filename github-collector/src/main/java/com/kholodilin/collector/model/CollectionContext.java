package com.kholodilin.collector.model;

/**
 * Narrow execution context for one running collection: authenticated origin and the local
 * observation-job id used for checkpoint scoping. Not a lease copy — no generation, payload or
 * fencing fields.
 */
public record CollectionContext(String trustedOrigin, long observationJobId) {
  public CollectionContext {
    if (trustedOrigin == null || trustedOrigin.isBlank()) {
      throw new IllegalArgumentException("trustedOrigin required");
    }
  }
}
