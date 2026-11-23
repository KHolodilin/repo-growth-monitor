package com.kholodilin.repogrowth.common.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.kholodilin.repogrowth.collection.domain.CollectionJobStatus;
import com.kholodilin.repogrowth.collection.persistence.CollectionJobJdbcRepository;
import com.kholodilin.repogrowth.search.domain.SearchRunStatus;
import com.kholodilin.repogrowth.search.persistence.SearchRunJdbcRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class QueueMetricsBinderTest {
  @Test
  void registersCollectionAndSearchQueueGauges() {
    var registry = new SimpleMeterRegistry();
    var jobs = mock(CollectionJobJdbcRepository.class);
    var searches = mock(SearchRunJdbcRepository.class);
    when(jobs.countByStatus(CollectionJobStatus.READY)).thenReturn(2);
    when(jobs.countByStatus(CollectionJobStatus.RUNNING)).thenReturn(1);
    when(jobs.countByStatus(CollectionJobStatus.FAILED)).thenReturn(3);
    when(searches.countByStatus(SearchRunStatus.READY)).thenReturn(4);
    when(searches.countByStatus(SearchRunStatus.FAILED)).thenReturn(5);
    new QueueMetricsBinder(registry, jobs, searches);
    assertThat(registry.get("collection.jobs.ready").gauge().value()).isEqualTo(2);
    assertThat(registry.get("collection.jobs.running").gauge().value()).isEqualTo(1);
    assertThat(registry.get("collection.jobs.failed").gauge().value()).isEqualTo(3);
    assertThat(registry.get("search.jobs.ready").gauge().value()).isEqualTo(4);
    assertThat(registry.get("search.jobs.failed").gauge().value()).isEqualTo(5);
  }
}
