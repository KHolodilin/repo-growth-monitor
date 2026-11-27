package com.kholodilin.repogrowth.search.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.kholodilin.repogrowth.search.model.SearchRunStatus;
import com.kholodilin.repogrowth.search.repository.SearchRunJdbcRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class SearchQueueMetricsBinderTest {
  @Test
  void registersSearchQueueGauges() {
    var registry = new SimpleMeterRegistry();
    var searches = mock(SearchRunJdbcRepository.class);
    when(searches.countByStatus(SearchRunStatus.READY)).thenReturn(4);
    when(searches.countByStatus(SearchRunStatus.FAILED)).thenReturn(5);
    new SearchQueueMetricsBinder(registry, searches);
    assertThat(registry.get("search.jobs.ready").gauge().value()).isEqualTo(4);
    assertThat(registry.get("search.jobs.failed").gauge().value()).isEqualTo(5);
  }
}
