package com.kholodilin.collector.model;

/** Durable attempt identity remains authoritative after replay records expire. */
public final class AttemptConflictException extends IllegalArgumentException {
  public AttemptConflictException() {
    super("Attempt already accepted with different content");
  }
}
