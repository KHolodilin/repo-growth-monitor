package com.kholodilin.collector.repository;

import com.kholodilin.idempotency.model.*;
import com.kholodilin.idempotency.spi.PersistenceStore;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Process-local PersistenceStore for the idempotency starter (CAS acquire). */
public final class InMemoryPersistenceStore implements PersistenceStore {
  private final ConcurrentHashMap<IdempotencyKey, IdempotencyRecord> records =
      new ConcurrentHashMap<>();

  @Override
  public Optional<IdempotencyRecord> find(IdempotencyKey key) {
    Objects.requireNonNull(key);
    return Optional.ofNullable(records.get(key));
  }

  @Override
  public boolean acquire(
      IdempotencyKey key, String requestHash, Instant createdAt, Instant expiresAt) {
    Objects.requireNonNull(key);
    Objects.requireNonNull(requestHash);
    Objects.requireNonNull(createdAt);
    Objects.requireNonNull(expiresAt);
    IdempotencyRecord created =
        IdempotencyRecord.processing(key, requestHash, createdAt, expiresAt);
    return records.putIfAbsent(key, created) == null;
  }

  @Override
  public void complete(
      IdempotencyKey key, String resultType, String resultPayload, Instant completedAt) {
    Objects.requireNonNull(key);
    Objects.requireNonNull(resultType);
    Objects.requireNonNull(resultPayload);
    Objects.requireNonNull(completedAt);
    IdempotencyRecord updated =
        records.compute(
            key,
            (k, existing) -> {
              if (existing == null || existing.status() != IdempotencyStatus.PROCESSING)
                throw new IllegalStateException(
                    "Idempotency complete for " + key + " affected 0 rows, expected exactly 1");
              return existing.completed(resultType, resultPayload, completedAt);
            });
    if (updated == null)
      throw new IllegalStateException(
          "Idempotency complete for " + key + " affected 0 rows, expected exactly 1");
  }

  @Override
  public void reject(
      IdempotencyKey key, String errorCode, String resultPayload, Instant completedAt) {
    Objects.requireNonNull(key);
    Objects.requireNonNull(errorCode);
    Objects.requireNonNull(completedAt);
    IdempotencyRecord updated =
        records.compute(
            key,
            (k, existing) -> {
              if (existing == null || existing.status() != IdempotencyStatus.PROCESSING)
                throw new IllegalStateException(
                    "Idempotency reject for " + key + " affected 0 rows, expected exactly 1");
              return existing.rejected(errorCode, resultPayload, completedAt);
            });
    if (updated == null)
      throw new IllegalStateException(
          "Idempotency reject for " + key + " affected 0 rows, expected exactly 1");
  }

  /** Test/helper hook to drop all process-local records. */
  public void clear() {
    records.clear();
  }
}
