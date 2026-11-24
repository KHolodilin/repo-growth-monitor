package com.kholodilin.collector.service;

import com.kholodilin.collector.model.CollectionContext;
import com.kholodilin.collector.repository.ObservationStore;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.json.JsonMapper;

/**
 * Service-owned per-observation checkpoints. Serializes typed values with Jackson; does not call
 * GitHub or parse GitHub JSON.
 */
@RequiredArgsConstructor
public final class ObservationCheckpoint {
  private final ObservationStore observations;
  private final JsonMapper json;

  public <T> T get(CollectionContext context, String key, Class<T> type, Supplier<T> load) {
    Objects.requireNonNull(context);
    Objects.requireNonNull(type);
    Objects.requireNonNull(load);
    var stored = observations.find(context.observationJobId(), key);
    if (stored.isPresent()) return json.readValue(stored.get(), type);
    T value = Objects.requireNonNull(load.get(), "observation");
    observations.save(context.observationJobId(), key, json.writeValueAsString(value));
    return value;
  }

  public void invalidate(CollectionContext context, String key) {
    observations.remove(context.observationJobId(), key);
  }

  public Instant observedAt(CollectionContext context) {
    return observations.firstObserved(context.observationJobId());
  }
}
