package com.kholodilin.collector.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kholodilin.collector.model.PayloadTooLarge;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class InMemoryObservationStoreTest {
  @Test
  void savesFindsAndRemovesPerJob() {
    var store = new InMemoryObservationStore(1024);

    store.save(1, "k", "payload");

    assertThat(store.find(1, "k")).contains("payload");
    assertThat(store.find(2, "k")).isEmpty();
    assertThat(store.find(1, "missing")).isEmpty();
    store.remove(1, "k");
    assertThat(store.find(1, "k")).isEmpty();
    store.remove(2, "k");
  }

  @Test
  void firstObservedIsStableUntilTheLastCheckpointIsRemoved() throws Exception {
    var store = new InMemoryObservationStore(1024);

    store.save(1, "a", "1");
    Instant first = store.firstObserved(1);
    Thread.sleep(5);
    store.save(1, "b", "2");

    assertThat(store.firstObserved(1)).isEqualTo(first);
    store.remove(1, "a");
    assertThat(store.firstObserved(1)).isEqualTo(first);
    store.remove(1, "b");
    assertThat(store.firstObserved(1)).isAfter(first);
  }

  @Test
  void totalBytesPerJobAreBoundedAndOverwritesReplaceTheirOwnShare() {
    var store = new InMemoryObservationStore(256);

    store.save(1, "a", "x".repeat(200));
    assertThatThrownBy(() -> store.save(1, "b", "y".repeat(100)))
        .isInstanceOf(PayloadTooLarge.class);
    store.save(1, "a", "z".repeat(250));

    assertThat(store.find(1, "a")).contains("z".repeat(250));
    assertThat(store.find(1, "b")).isEmpty();
  }

  @Test
  void rejectsInvalidKeysAndTooSmallLimits() {
    var store = new InMemoryObservationStore(256);

    assertThatThrownBy(() -> store.save(1, " ", "x")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> store.save(1, null, "x")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> store.save(1, "k".repeat(513), "x"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> store.save(1, "k", null)).isInstanceOf(NullPointerException.class);
    store.save(1, "k", "x");
    assertThatThrownBy(() -> store.find(1, "")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new InMemoryObservationStore(255))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
