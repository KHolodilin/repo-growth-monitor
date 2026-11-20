package com.kholodilin.collector.queue;

import com.kholodilin.collector.contracts.CollectorProtocol.JobState;
import java.util.Objects;

/**
 * Process-local execution states. {@link #READY} is the queued form of wire {@link
 * JobState#ACCEPTED}.
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

  /** Maps to the wire {@link JobState} exposed on status APIs. */
  public JobState toWire() {
    return this == READY ? JobState.ACCEPTED : JobState.valueOf(name());
  }

  public static LocalJobState ofTerminal(JobState state) {
    Objects.requireNonNull(state);
    if (!state.isTerminal()) {
      throw new IllegalArgumentException("Terminal state required");
    }
    return valueOf(state.name());
  }
}
