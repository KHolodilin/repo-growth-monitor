package com.kholodilin.repogrowth.collection.planner;

import com.kholodilin.repogrowth.catalog.repository.RepositoryJdbcRepository;
import com.kholodilin.repogrowth.collection.domain.CollectionRun;
import com.kholodilin.repogrowth.collection.persistence.CollectionJobJdbcRepository;
import com.kholodilin.repogrowth.collection.persistence.CollectionRunJdbcRepository;
import com.kholodilin.repogrowth.common.api.ApiException;
import com.kholodilin.repogrowth.traffic.persistence.TrafficJdbcRepository;
import java.time.LocalDate;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CollectionPlanner {

  public CollectionPlanner(
      RepositoryJdbcRepository repositoryJdbcRepository,
      CollectionRunJdbcRepository runRepository,
      CollectionJobJdbcRepository jobRepository,
      TrafficJdbcRepository trafficJdbcRepository) {
    Objects.requireNonNull(repositoryJdbcRepository);
    Objects.requireNonNull(runRepository);
    Objects.requireNonNull(jobRepository);
    Objects.requireNonNull(trafficJdbcRepository);
  }

  @Transactional
  public int planAll(LocalDate businessDate) {
    return planAll(businessDate, false);
  }

  @Transactional
  public int planAll(LocalDate businessDate, boolean snapshotDue) {
    throw unavailable();
  }

  @Transactional
  public CollectionRun planRepository(long repositoryId, LocalDate businessDate) {
    return planRepository(repositoryId, businessDate, false, false);
  }

  @Transactional
  public CollectionRun planRepository(
      long repositoryId, LocalDate businessDate, boolean recollect) {
    return planRepository(repositoryId, businessDate, recollect, false);
  }

  @Transactional
  public CollectionRun planRepository(
      long repositoryId, LocalDate businessDate, boolean recollect, boolean snapshotDue) {
    throw unavailable();
  }

  private static ApiException unavailable() {
    return ApiException.serviceUnavailable("Collector orchestration is required");
  }
}
