package com.kholodilin.collector.service;

import static org.assertj.core.api.Assertions.*;

import com.kholodilin.collector.model.CollectionContext;
import com.kholodilin.collector.model.PayloadTooLarge;
import com.kholodilin.collector.model.github.RepositoryObservation;
import com.kholodilin.collector.repository.InMemoryObservationStore;
import com.kholodilin.collector.repository.ObservationStore;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ObservationCheckpointTest {
  JsonMapper json = JsonMapper.builder().build();
  ObservationStore store = new InMemoryObservationStore(1024);
  ObservationCheckpoint checkpoint = new ObservationCheckpoint(store, json);
  CollectionContext context = new CollectionContext("rgm", 7);

  @Test
  void reusesStoredObservationWithoutCallingLoader() {
    AtomicInteger loads = new AtomicInteger();
    RepositoryObservation first =
        checkpoint.get(
            context,
            "repository",
            RepositoryObservation.class,
            () -> {
              loads.incrementAndGet();
              return new RepositoryObservation(0, 0, 0L, "{\"stargazers_count\":0}");
            });
    RepositoryObservation second =
        checkpoint.get(
            context,
            "repository",
            RepositoryObservation.class,
            () -> {
              loads.incrementAndGet();
              return new RepositoryObservation(9, 0, 0L, "{}");
            });

    assertThat(first.stars()).isZero();
    assertThat(second.stars()).isZero();
    assertThat(loads.get()).isEqualTo(1);
    assertThat(store.find(7, "repository")).isPresent();
  }

  @Test
  void persistsNewTypedObservation() {
    checkpoint.get(
        context,
        "repository",
        RepositoryObservation.class,
        () -> new RepositoryObservation(0, 0, null, "{\"stargazers_count\":0}"));
    assertThat(store.find(7, "repository")).isPresent();
    assertThat(store.find(7, "repository").orElseThrow()).contains("\"stars\":0");
    assertThat(store.find(8, "repository")).isEmpty();
  }

  @Test
  void invalidationForcesAFreshLoad() {
    AtomicInteger loads = new AtomicInteger();
    checkpoint.get(
        context,
        "repository",
        RepositoryObservation.class,
        () -> {
          loads.incrementAndGet();
          return new RepositoryObservation(0, 0, null, "{}");
        });
    checkpoint.invalidate(context, "repository");
    RepositoryObservation refreshed =
        checkpoint.get(
            context,
            "repository",
            RepositoryObservation.class,
            () -> {
              loads.incrementAndGet();
              return new RepositoryObservation(3, 0, null, "{}");
            });

    assertThat(refreshed.stars()).isEqualTo(3);
    assertThat(loads.get()).isEqualTo(2);
  }

  @Test
  void firstObservedStaysStableAcrossKeysAndResetsWhenEmpty() throws Exception {
    checkpoint.get(
        context,
        "repository",
        RepositoryObservation.class,
        () -> new RepositoryObservation(0, 0, null, "{}"));
    Instant first = checkpoint.observedAt(context);
    Thread.sleep(5);
    checkpoint.get(context, "readme", Boolean.class, () -> Boolean.TRUE);

    assertThat(checkpoint.observedAt(context)).isEqualTo(first);
    checkpoint.invalidate(context, "repository");
    checkpoint.invalidate(context, "readme");
    assertThat(checkpoint.observedAt(context)).isAfter(first);
  }

  @Test
  void checkpointByteLimitIsPreserved() {
    var tight = new ObservationCheckpoint(new InMemoryObservationStore(256), json);
    tight.get(context, "big", String.class, () -> "x".repeat(200));

    assertThatThrownBy(() -> tight.get(context, "more", String.class, () -> "y".repeat(100)))
        .isInstanceOf(PayloadTooLarge.class);
  }

  @Test
  void partialCollectionReusesEarlierCheckpointsOnRetry() {
    AtomicInteger repositoryLoads = new AtomicInteger();
    AtomicInteger readmeLoads = new AtomicInteger();

    checkpoint.get(
        context,
        "repository",
        RepositoryObservation.class,
        () -> {
          repositoryLoads.incrementAndGet();
          return new RepositoryObservation(0, 0, null, "{}");
        });
    assertThatThrownBy(() ->
            checkpoint.get(
                context,
                "readme",
                String.class,
                () -> {
                  readmeLoads.incrementAndGet();
                  throw new RuntimeException("transient");
                }))
        .hasMessageContaining("transient");

    checkpoint.get(
        context,
        "repository",
        RepositoryObservation.class,
        () -> {
          repositoryLoads.incrementAndGet();
          return new RepositoryObservation(9, 0, null, "{}");
        });
    String readme =
        checkpoint.get(
            context,
            "readme",
            String.class,
            () -> {
              readmeLoads.incrementAndGet();
              return "README.md";
            });

    assertThat(readme).isEqualTo("README.md");
    assertThat(repositoryLoads.get()).isEqualTo(1);
    assertThat(readmeLoads.get()).isEqualTo(2);
  }
}
