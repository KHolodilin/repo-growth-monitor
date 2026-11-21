package com.kholodilin.collector.model;

/** Thrown when cumulative or single observation bytes exceed the configured payload bound. */
public final class PayloadTooLarge extends RuntimeException {
  public PayloadTooLarge() {
    super("Collector observation payload exceeds the configured bound");
  }
}
