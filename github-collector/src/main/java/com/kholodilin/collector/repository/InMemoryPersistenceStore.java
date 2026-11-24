package com.kholodilin.collector.repository;

import com.kholodilin.idempotency.model.*;
import com.kholodilin.idempotency.spi.PersistenceStore;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

/**
 * Process-local PersistenceStore for the idempotency starter.
 *
 * <p>Mirrors JDBC unique-index semantics: concurrent {@link #acquire} calls for the same key block
 * while a {@code PROCESSING} row is held, then either insert or return {@code false} so the caller
 * can replay the committed terminal record. Same-thread re-acquire while still {@code PROCESSING}
 * returns {@code false} without waiting (matches single-executor unit tests).
 */
public final class InMemoryPersistenceStore implements PersistenceStore {
  private record Slot(IdempotencyRecord record, Thread owner) {}

  private final ConcurrentHashMap<IdempotencyKey, Slot> slots = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<IdempotencyKey, Object> monitors = new ConcurrentHashMap<>();

  @Override
  public Optional<IdempotencyRecord> find(IdempotencyKey key) {
    Objects.requireNonNull(key);
    Slot slot = slots.get(key);
    return slot == null ? Optional.empty() : Optional.of(slot.record());
  }

  @Override
  public boolean acquire(
      IdempotencyKey key, String requestHash, Instant createdAt, Instant expiresAt) {
    Objects.requireNonNull(key);
    Objects.requireNonNull(requestHash);
    Objects.requireNonNull(createdAt);
    // expiresAt is @Nullable on PersistenceStore (idempotency-core 1.2.0); record keeps null.
    Object monitor = monitors.computeIfAbsent(key, ignored -> new Object());
    synchronized (monitor) {
      while (true) {
        AtomicReference<Boolean> outcome = new AtomicReference<>();
        slots.compute(
            key,
            (ignored, existing) -> {
              if (existing == null) {
                outcome.set(Boolean.TRUE);
                return new Slot(
                    IdempotencyRecord.processing(key, requestHash, createdAt, expiresAt),
                    Thread.currentThread());
              }
              if (existing.record().status().isTerminal()
                  || Thread.currentThread().equals(existing.owner())) {
                outcome.set(Boolean.FALSE);
                return existing;
              }
              return existing;
            });
        Boolean decided = outcome.get();
        if (decided != null) {
          return decided;
        }
        try {
          monitor.wait();
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(
              "Interrupted while waiting for idempotency acquire " + key, interrupted);
        }
      }
    }
  }

  @Override
  public void complete(
      IdempotencyKey key, String resultType, String resultPayload, Instant completedAt) {
    Objects.requireNonNull(key);
    Objects.requireNonNull(completedAt);
    // resultType and resultPayload are @Nullable on PersistenceStore; record keeps nulls.
    finish(
        key,
        existing -> {
          if (existing == null || existing.status() != IdempotencyStatus.PROCESSING) {
            throw new IllegalStateException(
                "Idempotency complete for " + key + " affected 0 rows, expected exactly 1");
          }
          return existing.completed(resultType, resultPayload, completedAt);
        });
  }

  @Override
  public void reject(
      IdempotencyKey key, String errorCode, String resultPayload, Instant completedAt) {
    Objects.requireNonNull(key);
    Objects.requireNonNull(errorCode);
    Objects.requireNonNull(completedAt);
    // resultPayload maps to SPI detailsPayload (@Nullable); record keeps null.
    finish(
        key,
        existing -> {
          if (existing == null || existing.status() != IdempotencyStatus.PROCESSING) {
            throw new IllegalStateException(
                "Idempotency reject for " + key + " affected 0 rows, expected exactly 1");
          }
          return existing.rejected(errorCode, resultPayload, completedAt);
        });
  }

  /** Test/helper hook to drop all process-local records. */
  public void clear() {
    for (Object monitor : monitors.values()) {
      synchronized (monitor) {
        monitor.notifyAll();
      }
    }
    slots.clear();
    monitors.clear();
  }

  private void finish(IdempotencyKey key, UnaryOperator<IdempotencyRecord> update) {
    Object monitor = monitors.computeIfAbsent(key, ignored -> new Object());
    synchronized (monitor) {
      slots.compute(
          key,
          (ignored, current) -> {
            IdempotencyRecord updated = update.apply(current == null ? null : current.record());
            return new Slot(updated, null);
          });
      monitor.notifyAll();
    }
  }
}
