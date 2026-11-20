package com.kholodilin.collector.integration.delivery;

/** What one delivery attempt achieved, with the transport details already interpreted. */
public enum DeliveryOutcome {
  /** The destination confirmed receipt (HTTP 204). */
  DELIVERED,
  /** Timeout, throttling, server error or transport failure: try again later. */
  RETRYABLE,
  /** The destination permanently refused the result. */
  REJECTED,
  /** No trusted destination or service credential is configured for the origin/ref. */
  DESTINATION_UNAVAILABLE
}
