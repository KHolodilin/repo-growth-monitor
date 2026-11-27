package com.kholodilin.repogrowth.common.observability;

import com.kholodilin.repogrowth.collection.model.CollectionJobStatus;
import com.kholodilin.repogrowth.collection.repository.CollectionJobJdbcRepository;
import com.kholodilin.repogrowth.search.model.SearchRunStatus;
import com.kholodilin.repogrowth.search.repository.SearchRunJdbcRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class QueueMetricsBinder {

  public QueueMetricsBinder(
      MeterRegistry registry,
      CollectionJobJdbcRepository collectionJobJdbcRepository,
      SearchRunJdbcRepository searchRunJdbcRepository) {
    Gauge.builder(
            "collection.jobs.ready",
            collectionJobJdbcRepository,
            repo -> repo.countByStatus(CollectionJobStatus.READY))
        .register(registry);
    Gauge.builder(
            "collection.jobs.running",
            collectionJobJdbcRepository,
            repo -> repo.countByStatus(CollectionJobStatus.RUNNING))
        .register(registry);
    Gauge.builder(
            "collection.jobs.failed",
            collectionJobJdbcRepository,
            repo -> repo.countByStatus(CollectionJobStatus.FAILED))
        .register(registry);
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
