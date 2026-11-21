package com.kholodilin.collector.model;

/**
 * Process-local execution states. {@link #READY} is the queued form of the wire ACCEPTED state;
 * mapping to wire values lives in the service layer.
 */
public enum LocalJobState {
  READY,
  RUNNING,
  RETRY,
  SUCCEEDED,
  FAILED,
  CANCELLED;

  public boolean isDue() {
    return this == READY || this == RETRY;
  }

  public boolean isRunning() {
    return this == RUNNING;
  }

  public boolean isPending() {
    return isDue() || isRunning();
  }

  public boolean isTerminal() {
    return this == SUCCEEDED || this == FAILED || this == CANCELLED;
  }
}
