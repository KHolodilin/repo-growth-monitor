package com.kholodilin.collector.idempotency;

import static org.assertj.core.api.Assertions.*;

import com.kholodilin.idempotency.model.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class InMemoryPersistenceStoreTest {
  InMemoryPersistenceStore store = new InMemoryPersistenceStore();
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
}
