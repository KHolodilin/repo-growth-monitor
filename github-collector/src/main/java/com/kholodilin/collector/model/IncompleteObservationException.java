package com.kholodilin.collector.model;

/** Assigned collection could not form a complete observation from GitHub data. */
public final class IncompleteObservationException extends RuntimeException {
  public IncompleteObservationException() {
    super("Incomplete GitHub observation");
  }
}
