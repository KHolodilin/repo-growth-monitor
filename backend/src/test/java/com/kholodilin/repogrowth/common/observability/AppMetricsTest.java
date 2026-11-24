package com.kholodilin.repogrowth.common.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class AppMetricsTest {
  @Test
  void recordsTrackedRepositoriesRequestsAndTimers() {
    var registry = new SimpleMeterRegistry();
    var metrics = new AppMetrics(registry);
    metrics.setTrackedRepositories(3);
    assertThat(registry.get("repositories.tracked").gauge().value()).isEqualTo(3);
    metrics.githubRequest("search", true);
    metrics.githubRequest("search", false);
    assertThat(registry.get("github.api.requests").counter().count()).isEqualTo(2);
    assertThat(registry.get("github.api.errors").counter().count()).isEqualTo(1);
    var sample = metrics.startTimer();
    metrics.recordDuration(sample, "collection.duration");
    assertThat(registry.get("collection.duration").timer().count()).isEqualTo(1);
    metrics.collectionJobs("READY", () -> 4);
    assertThat(registry.get("collection.jobs.ready").gauge().value()).isEqualTo(4);
  }
}
