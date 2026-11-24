package com.kholodilin.collector.repository;

import static org.assertj.core.api.Assertions.*;

import com.kholodilin.idempotency.model.*;
import com.kholodilin.idempotency.spi.PersistenceStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class InMemoryPersistenceStoreTest {
  /** Exercise the SPI contract, not only the concrete class. */
  PersistenceStore store = new InMemoryPersistenceStore();

  IdempotencyKey key = new IdempotencyKey("ACCEPT_COLLECTOR_JOB", "scoped-key");
  Instant created = Instant.parse("2027-01-01T00:00:00Z");
  Instant expires = Instant.parse("2027-01-08T00:00:00Z");

  @Test
  void acquireCompleteAndReplayFind() {
    assertThat(store.acquire(key, "hash-a", created, expires)).isTrue();
    assertThat(store.acquire(key, "hash-a", created, expires)).isFalse();
    assertThat(store.find(key))
        .get()
        .extracting(IdempotencyRecord::status, IdempotencyRecord::requestHash)
        .containsExactly(IdempotencyStatus.PROCESSING, "hash-a");
    store.complete(key, "JobAccepted", "{\"ok\":true}", Instant.parse("2027-01-01T00:01:00Z"));
    assertThat(store.find(key))
        .get()
        .extracting(IdempotencyRecord::status, IdempotencyRecord::resultPayload)
        .containsExactly(IdempotencyStatus.COMPLETED, "{\"ok\":true}");
    assertThatThrownBy(() -> store.complete(key, "x", "{}", Instant.now()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void rejectFromProcessing() {
    assertThat(store.acquire(key, "hash-b", created, expires)).isTrue();
    store.reject(key, "CAPACITY", "{}", Instant.parse("2027-01-01T00:02:00Z"));
    assertThat(store.find(key))
        .get()
        .extracting(IdempotencyRecord::status, IdempotencyRecord::errorCode)
        .containsExactly(IdempotencyStatus.REJECTED, "CAPACITY");
    assertThatThrownBy(() -> store.reject(key, "X", "{}", Instant.now()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void acquirePreservesNullExpiresAtFromSpiContract() {
    assertThat(store.acquire(key, "hash-null-exp", created, null)).isTrue();
    assertThat(store.find(key))
        .get()
        .extracting(IdempotencyRecord::expiresAt, IdempotencyRecord::status)
        .containsExactly(null, IdempotencyStatus.PROCESSING);
  }

  @Test
  void completePreservesNullResultTypeAndPayloadFromSpiContract() {
    assertThat(store.acquire(key, "hash-null-complete", created, expires)).isTrue();
    Instant done = Instant.parse("2027-01-01T00:03:00Z");
    store.complete(key, null, null, done);
    assertThat(store.find(key))
        .get()
        .extracting(
            IdempotencyRecord::status,
            IdempotencyRecord::resultType,
            IdempotencyRecord::resultPayload,
            IdempotencyRecord::completedAt)
        .containsExactly(IdempotencyStatus.COMPLETED, null, null, done);
  }

  @Test
  void rejectPreservesNullDetailsPayloadFromSpiContract() {
    assertThat(store.acquire(key, "hash-null-reject", created, expires)).isTrue();
    Instant done = Instant.parse("2027-01-01T00:04:00Z");
    store.reject(key, "CAPACITY", null, done);
    assertThat(store.find(key))
        .get()
        .extracting(
            IdempotencyRecord::status,
            IdempotencyRecord::errorCode,
            IdempotencyRecord::resultPayload,
            IdempotencyRecord::completedAt)
        .containsExactly(IdempotencyStatus.REJECTED, "CAPACITY", null, done);
  }

  @Test
  void concurrentAcquireBlocksUntilTerminalThenLoses() throws Exception {
    assertThat(store.acquire(key, "hash-a", created, expires)).isTrue();
    AtomicBoolean started = new AtomicBoolean();
    try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
      Future<Boolean> lost =
          pool.submit(() -> {
            started.set(true);
            return store.acquire(key, "hash-a", created, expires);
          });
      awaitTrue(started);
      assertThat(lost.isDone()).isFalse();
      store.complete(key, "JobAccepted", "{}", Instant.parse("2027-01-01T00:01:00Z"));
      assertThat(lost.get(2, TimeUnit.SECONDS)).isFalse();
      assertThat(store.find(key))
          .get()
          .extracting(IdempotencyRecord::status)
          .isEqualTo(IdempotencyStatus.COMPLETED);
    }
  }

  @Test
  void concurrentWinnersSerializeToSingleAcquire() throws Exception {
    try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
      List<Callable<Boolean>> tasks = new ArrayList<>();
      for (int i = 0; i < 20; i++) {
        tasks.add(() -> store.acquire(key, "hash-a", created, expires));
      }
      List<Future<Boolean>> futures = new ArrayList<>();
      for (Callable<Boolean> task : tasks) {
        futures.add(
            pool.submit(() -> {
              boolean won = task.call();
              if (won) {
                store.complete(key, "JobAccepted", "{}", Instant.now());
              }
              return won;
            }));
      }
      int winners = 0;
      for (Future<Boolean> future : futures) {
        if (Boolean.TRUE.equals(future.get(5, TimeUnit.SECONDS))) {
          winners++;
        }
      }
      assertThat(winners).isEqualTo(1);
      assertThat(store.find(key))
          .get()
          .extracting(IdempotencyRecord::status)
          .isEqualTo(IdempotencyStatus.COMPLETED);
    }
  }

  private static void awaitTrue(AtomicBoolean flag) throws InterruptedException {
    for (int i = 0; i < 200 && !flag.get(); i++) {
      Thread.sleep(5);
    }
    assertThat(flag).isTrue();
  }
}
