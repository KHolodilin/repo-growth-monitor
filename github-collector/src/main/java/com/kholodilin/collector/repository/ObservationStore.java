package com.kholodilin.collector.repository;

import java.util.Optional;

/** Bounded per-job observation checkpoints held while a collection is running. */
public interface ObservationStore {
  Optional<String> find(long observationJobId, String key);

  void save(long observationJobId, String key, String payload);

  void remove(long observationJobId, String key);

  default java.time.Instant firstObserved(long observationJobId) {
    return java.time.Instant.now();
  }
}
