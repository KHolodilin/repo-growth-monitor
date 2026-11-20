package com.kholodilin.collector.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kholodilin.collector.model.PayloadTooLarge;
import com.kholodilin.collector.repository.JobQueue.Lease;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InMemoryObservationStoreTest {
  private static Lease lease(long id) {
    return new Lease(
        id, "owner", 1, "{}", Instant.now().plusSeconds(60), "rgm", UUID.randomUUID(), 0);
  }

  @Test
  void savesFindsAndRemovesPerLease() {
    var store = new InMemoryObservationStore(1024);
    var one = lease(1);
    var two = lease(2);

    store.save(one, "k", "payload");

    assertThat(store.find(one, "k")).contains("payload");
    assertThat(store.find(two, "k")).isEmpty();
    assertThat(store.find(one, "missing")).isEmpty();
    store.remove(one, "k");
    assertThat(store.find(one, "k")).isEmpty();
    store.remove(two, "k");
  }

  @Test
  void firstObservedIsStableUntilTheLastCheckpointIsRemoved() throws Exception {
    var store = new InMemoryObservationStore(1024);
    var lease = lease(1);

    store.save(lease, "a", "1");
    Instant first = store.firstObserved(lease);
    Thread.sleep(5);
    store.save(lease, "b", "2");

    assertThat(store.firstObserved(lease)).isEqualTo(first);
    store.remove(lease, "a");
    assertThat(store.firstObserved(lease)).isEqualTo(first);
    store.remove(lease, "b");
    assertThat(store.firstObserved(lease)).isAfter(first);
  }

  @Test
  void totalBytesPerLeaseAreBoundedAndOverwritesReplaceTheirOwnShare() {
    var store = new InMemoryObservationStore(256);
    var lease = lease(1);

    store.save(lease, "a", "x".repeat(200));
    assertThatThrownBy(() -> store.save(lease, "b", "y".repeat(100)))
        .isInstanceOf(PayloadTooLarge.class);
    store.save(lease, "a", "z".repeat(250));

    assertThat(store.find(lease, "a")).contains("z".repeat(250));
    assertThat(store.find(lease, "b")).isEmpty();
  }

  @Test
  void rejectsInvalidKeysAndTooSmallLimits() {
    var store = new InMemoryObservationStore(256);
    var lease = lease(1);

    assertThatThrownBy(() -> store.save(lease, " ", "x"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> store.save(lease, null, "x"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> store.save(lease, "k".repeat(513), "x"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> store.save(lease, "k", null)).isInstanceOf(NullPointerException.class);
    store.save(lease, "k", "x");
    assertThatThrownBy(() -> store.find(lease, "")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new InMemoryObservationStore(255))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
