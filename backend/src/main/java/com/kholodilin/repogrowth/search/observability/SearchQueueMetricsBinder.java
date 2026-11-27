package com.kholodilin.repogrowth.search.observability;

import com.kholodilin.repogrowth.search.model.SearchRunStatus;
import com.kholodilin.repogrowth.search.repository.SearchRunJdbcRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class SearchQueueMetricsBinder {

  public SearchQueueMetricsBinder(
      MeterRegistry registry, SearchRunJdbcRepository searchRunJdbcRepository) {
    Gauge.builder(
            "search.jobs.ready",
            searchRunJdbcRepository,
            repo -> repo.countByStatus(SearchRunStatus.READY))
        .register(registry);
    Gauge.builder(
            "search.jobs.failed",
            searchRunJdbcRepository,
            repo -> repo.countByStatus(SearchRunStatus.FAILED))
        .register(registry);
  }
}
