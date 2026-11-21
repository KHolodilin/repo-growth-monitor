package com.kholodilin.collector.contracts;

/**
 * Shared wire header names between RGM and Collector. Values are protocol strings only — no Spring
 * types.
 */
public final class CollectorHttpHeaders {
  /** Idempotent accept key; must equal {@code attemptId} for job submission. */
  public static final String IDEMPOTENCY_KEY = "Idempotency-Key";

  private CollectorHttpHeaders() {}
}
