package com.kholodilin.repogrowth.collection.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.kholodilin.repogrowth.collection.model.CollectionJobStatus;
import com.kholodilin.repogrowth.collection.repository.CollectionJobJdbcRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class CollectionQueueMetricsBinderTest {
  @Test
  void registersCollectionQueueGauges() {
    var registry = new SimpleMeterRegistry();
    var jobs = mock(CollectionJobJdbcRepository.class);
    when(jobs.countByStatus(CollectionJobStatus.READY)).thenReturn(2);
    when(jobs.countByStatus(CollectionJobStatus.RUNNING)).thenReturn(1);
    when(jobs.countByStatus(CollectionJobStatus.FAILED)).thenReturn(3);
    new CollectionQueueMetricsBinder(registry, jobs);
    assertThat(registry.get("collection.jobs.ready").gauge().value()).isEqualTo(2);
    assertThat(registry.get("collection.jobs.running").gauge().value()).isEqualTo(1);
    assertThat(registry.get("collection.jobs.failed").gauge().value()).isEqualTo(3);
  }
}
