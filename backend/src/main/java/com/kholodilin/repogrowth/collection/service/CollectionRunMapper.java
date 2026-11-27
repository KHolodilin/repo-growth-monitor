package com.kholodilin.repogrowth.collection.service;

import com.kholodilin.repogrowth.collection.model.CollectionJob;
import com.kholodilin.repogrowth.collection.model.CollectionRun;
import com.kholodilin.repogrowth.collection.repository.CollectionJobJdbcRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Maps collection runs to API DTOs. Lives outside {@code CollectionRunResourceController} so application
 * services never depend on the HTTP layer.
 */
@Component
public class CollectionRunMapper {

  private final CollectionJobJdbcRepository jobRepository;

  public CollectionRunMapper(CollectionJobJdbcRepository jobRepository) {
    this.jobRepository = jobRepository;
  }

  public CollectionRunView toView(CollectionRun run) {
    List<CollectionJob> jobs = jobRepository.findByRun(run.id());
    return new CollectionRunView(
        run.id(),
        run.repositoryId(),
        run.businessDate().toString(),
        run.status().name(),
        run.plannedJobs(),
        run.successfulJobs(),
        run.failedJobs(),
        run.createdAt(),
        run.completedAt(),
        jobs.stream()
            .map(job ->
                new CollectionJobView(
                    job.id(),
                    job.jobType().name(),
                    job.status().name(),
                    job.attempt(),
                    job.errorCode(),
                    job.errorMessage(),
                    job.startedAt(),
                    job.completedAt()))
            .toList());
  }

  public record CollectionRunView(
      long id,
      long repositoryId,
      String businessDate,
      String status,
      int plannedJobs,
      int successfulJobs,
      int failedJobs,
      Instant createdAt,
      Instant completedAt,
      List<CollectionJobView> jobs) {}

  public record CollectionJobView(
      long id,
      String jobType,
      String status,
      int attempt,
      String errorCode,
      String errorMessage,
      Instant startedAt,
      Instant completedAt) {}

  /** Wire-compatible admin DTO shared by HTTP and other modules (no controller dependency). */
  public CollectionRunResponse toResponse(CollectionRunView view) {
    return new CollectionRunResponse(
        view.id(),
        view.repositoryId(),
        view.businessDate(),
        view.status(),
        view.plannedJobs(),
        view.successfulJobs(),
        view.failedJobs(),
        view.createdAt(),
        view.completedAt(),
        view.jobs().stream().map(this::toJobResponse).toList());
  }

  private CollectionJobResponse toJobResponse(CollectionJobView job) {
    return new CollectionJobResponse(
        job.id(),
        job.jobType(),
        job.status(),
        job.attempt(),
        job.errorCode(),
        job.errorMessage(),
        job.startedAt(),
        job.completedAt());
  }

  public record CollectionRunResponse(
      long id,
      long repositoryId,
      String businessDate,
      String status,
      int plannedJobs,
      int successfulJobs,
      int failedJobs,
      Instant createdAt,
      Instant completedAt,
      List<CollectionJobResponse> jobs) {}

  public record CollectionJobResponse(
      long id,
      String jobType,
      String status,
      int attempt,
      String errorCode,
      String errorMessage,
      Instant startedAt,
      Instant completedAt) {}
}
