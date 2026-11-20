package com.kholodilin.collector.queue;

/** Accept rejected because the in-memory queue or request size ceiling was hit. */
public final class CapacityExceededException extends IllegalStateException {
  public CapacityExceededException(String message) {
    super(message);
  }
}
