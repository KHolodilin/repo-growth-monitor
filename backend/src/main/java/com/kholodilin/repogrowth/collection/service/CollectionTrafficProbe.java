package com.kholodilin.repogrowth.collection.service;

import com.kholodilin.repogrowth.collection.model.CollectionJob;
import com.kholodilin.repogrowth.collection.model.CollectionRun;
import com.kholodilin.repogrowth.collection.repository.CollectionJobJdbcRepository;
import com.kholodilin.repogrowth.collection.repository.CollectionRunJdbcRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Narrow collection reads for Traffic / Portfolio traffic-gap and job status projections. Keeps
 * foreign modules off collection repositories.
 */
@Service
public class CollectionTrafficProbe {

  private final CollectionRunJdbcRepository runs;
  private final CollectionJobJdbcRepository jobs;

  public CollectionTrafficProbe(
      CollectionRunJdbcRepository runs, CollectionJobJdbcRepository jobs) {
    this.runs = runs;
    this.jobs = jobs;
  }

  public Optional<CollectionRun> find(long repositoryId, LocalDate businessDate) {
    return runs.find(repositoryId, businessDate);
  }

  public Optional<CollectionRun> latestForRepository(long repositoryId) {
    return runs.latestForRepository(repositoryId);
  }

  public List<CollectionRun> latestPerTrackedRepository() {
    return runs.latestPerTrackedRepository();
  }

  public Instant latestCompletedAtForTracked() {
    return runs.latestCompletedAtForTracked();
  }

  public List<CollectionRun> trackedOn(LocalDate businessDate) {
    return runs.trackedOn(businessDate);
  }

  public List<CollectionJob> findByRun(long runId) {
    return jobs.findByRun(runId);
  }

  public List<CollectionJob> findByRunIds(List<Long> runIds) {
    return jobs.findByRunIds(runIds);
  }
}
