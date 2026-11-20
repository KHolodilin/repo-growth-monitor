package com.kholodilin.collector.execution;

import com.kholodilin.collector.queue.JobQueue.Lease;
import java.util.Optional;

/** Bounded per-job observation checkpoints held while a lease is running. */
public interface ObservationStore {
  Optional<String> find(Lease lease, String key);

  void save(Lease lease, String key, String payload);

  void remove(Lease lease, String key);

  default java.time.Instant firstObserved(Lease lease) {
    return java.time.Instant.now();
  }

  /** Thrown when cumulative or single observation bytes exceed the configured payload bound. */
  final class PayloadTooLarge extends RuntimeException {
    public PayloadTooLarge() {
      super("Collector observation payload exceeds the configured bound");
    }
  }
}
