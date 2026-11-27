package com.kholodilin.repogrowth.collection.observability;

import com.kholodilin.repogrowth.collection.model.CollectionJobStatus;
import com.kholodilin.repogrowth.collection.repository.CollectionJobJdbcRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class CollectionQueueMetricsBinder {

  public CollectionQueueMetricsBinder(
      MeterRegistry registry, CollectionJobJdbcRepository collectionJobJdbcRepository) {
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
  }
}
