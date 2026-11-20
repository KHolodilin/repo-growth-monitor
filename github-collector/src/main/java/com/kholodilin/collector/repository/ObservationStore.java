package com.kholodilin.collector.repository;

import com.kholodilin.collector.repository.JobQueue.Lease;
import java.util.Optional;

/** Bounded per-job observation checkpoints held while a lease is running. */
public interface ObservationStore {
  Optional<String> find(Lease lease, String key);

  void save(Lease lease, String key, String payload);

  void remove(Lease lease, String key);

  default java.time.Instant firstObserved(Lease lease) {
    return java.time.Instant.now();
  }
}
