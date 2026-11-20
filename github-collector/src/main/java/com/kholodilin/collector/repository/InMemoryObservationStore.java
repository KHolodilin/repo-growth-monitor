package com.kholodilin.collector.repository;

import com.kholodilin.collector.model.PayloadTooLarge;
import com.kholodilin.collector.repository.JobQueue.Lease;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Process-local observation checkpoints for one RUNNING lease. */
public final class InMemoryObservationStore implements ObservationStore {
  private final int maximum;
  private final ConcurrentHashMap<Long, ConcurrentHashMap<String, Entry>> byJob =
      new ConcurrentHashMap<>();
  private final ConcurrentHashMap<Long, Instant> first = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<Long, Object> locks = new ConcurrentHashMap<>();

  public InMemoryObservationStore(int maximum) {
    if (maximum < 256)
      throw new IllegalArgumentException("Observation maximum must be at least 256 bytes");
    this.maximum = maximum;
  }

  private record Entry(String payload, int bytes, Instant observedAt) {}

  private static String requireKey(String key) {
    if (key == null || key.isBlank() || key.length() > 512)
      throw new IllegalArgumentException("Invalid observation key");
    return key;
  }

  @Override
  public Optional<String> find(Lease lease, String key) {
    ConcurrentHashMap<String, Entry> map = byJob.get(lease.id());
    if (map == null) return Optional.empty();
    Entry entry = map.get(requireKey(key));
    return entry == null ? Optional.empty() : Optional.of(entry.payload());
  }

  @Override
  public void save(Lease lease, String key, String payload) {
    Objects.requireNonNull(payload);
    String observationKey = requireKey(key);
    int bytes = payload.getBytes(StandardCharsets.UTF_8).length;
    ConcurrentHashMap<String, Entry> map =
        byJob.computeIfAbsent(lease.id(), id -> new ConcurrentHashMap<>());
    Object lock = locks.computeIfAbsent(lease.id(), id -> new Object());
    synchronized (lock) {
      long existing =
          map.entrySet().stream()
              .filter(e -> !e.getKey().equals(observationKey))
              .mapToLong(e -> e.getValue().bytes())
              .sum();
      if (existing + bytes > maximum) throw new PayloadTooLarge();
      Instant at = Instant.now();
      map.put(observationKey, new Entry(payload, bytes, at));
      first.putIfAbsent(lease.id(), at);
    }
  }

  @Override
  public void remove(Lease lease, String key) {
    ConcurrentHashMap<String, Entry> map = byJob.get(lease.id());
    if (map == null) return;
    map.remove(requireKey(key));
    if (map.isEmpty()) {
      byJob.remove(lease.id(), map);
      first.remove(lease.id());
      locks.remove(lease.id());
    }
  }

  @Override
  public Instant firstObserved(Lease lease) {
    return first.getOrDefault(lease.id(), Instant.now());
  }
}
