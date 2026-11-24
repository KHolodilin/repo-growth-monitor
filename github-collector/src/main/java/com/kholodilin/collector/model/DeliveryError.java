package com.kholodilin.collector.model;

/** Terminal delivery failure reasons written into dead-letter rows. */
public enum DeliveryError {
  DESTINATION_UNAVAILABLE,
  REJECTED,
  RETRY_EXHAUSTED
}
