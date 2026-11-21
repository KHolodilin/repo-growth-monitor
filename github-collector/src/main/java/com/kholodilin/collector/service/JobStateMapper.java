package com.kholodilin.collector.service;

import com.kholodilin.collector.contracts.CollectorProtocol.JobState;
import com.kholodilin.collector.model.LocalJobState;

/** Maps process-local execution states to the wire {@link JobState} exposed on status APIs. */
final class JobStateMapper {
  private JobStateMapper() {}

  /** {@link LocalJobState#READY} is the queued form of wire {@link JobState#ACCEPTED}. */
  static JobState toWire(LocalJobState state) {
    return state == LocalJobState.READY ? JobState.ACCEPTED : JobState.valueOf(state.name());
  }
}
