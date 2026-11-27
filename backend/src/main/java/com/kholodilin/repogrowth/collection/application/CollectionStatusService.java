package com.kholodilin.repogrowth.collection.application;

import com.kholodilin.repogrowth.auth.security.CurrentUserAccess;
import com.kholodilin.repogrowth.catalog.model.Repository;
import com.kholodilin.repogrowth.catalog.model.RepositoryVisibility;
import com.kholodilin.repogrowth.catalog.service.RepositoryService;
import com.kholodilin.repogrowth.collection.domain.CollectionJob;
import com.kholodilin.repogrowth.collection.domain.CollectionRun;
import com.kholodilin.repogrowth.collection.persistence.CollectionJobJdbcRepository;
import com.kholodilin.repogrowth.collection.persistence.CollectionRunJdbcRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Compact collection status for header polling — replaces full dashboard reads for Layout progress.
 */
@Service
public class CollectionStatusService {

  private final RepositoryService repositories;
  private final CollectionRunJdbcRepository runs;
  private final CollectionJobJdbcRepository jobs;
  private final CurrentUserAccess currentUser;

  public CollectionStatusService(
      RepositoryService repositories,
      CollectionRunJdbcRepository runs,
      CollectionJobJdbcRepository jobs,
      CurrentUserAccess currentUser) {
    this.repositories = repositories;
    this.runs = runs;
    this.jobs = jobs;
    this.currentUser = currentUser;
  }

  public CollectionStatusResponse status(String ownerLogin) {
    List<Repository> tracked = visibleTracked(repositories.tracked());
    if (ownerLogin != null && !ownerLogin.isBlank()) {
      String needle = ownerLogin.trim().toLowerCase(Locale.ROOT) + "/";
      tracked =
          tracked.stream()
              .filter(repo -> repo.fullName().toLowerCase(Locale.ROOT).startsWith(needle))
              .toList();
    }
    Map<Long, CollectionRun> runByRepo =
        runs.latestPerTrackedRepository().stream()
            .collect(Collectors.toMap(CollectionRun::repositoryId, run -> run, (a, b) -> a));
    List<Long> runIds = runByRepo.values().stream().map(CollectionRun::id).distinct().toList();
    Map<Long, List<CollectionJob>> jobsByRun =
        jobs.findByRunIds(runIds).stream()
            .collect(Collectors.groupingBy(CollectionJob::collectionRunId));
    Instant lastSync = runs.latestCompletedAtForTracked();
    List<RepositoryStatus> rows = new ArrayList<>();
    for (Repository repository : tracked) {
      CollectionRun run = runByRepo.get(repository.id());
      List<JobStatus> jobStatuses =
          run == null
              ? List.of()
              : jobsByRun.getOrDefault(run.id(), List.of()).stream()
                  .map(job ->
                      new JobStatus(job.jobType().name(), job.status().name(), job.errorMessage()))
                  .toList();
      rows.add(
          new RepositoryStatus(
              repository.id(),
              repository.fullName(),
              run == null ? null : run.status().name(),
              jobStatuses));
    }
    return new CollectionStatusResponse(lastSync, rows);
  }

  private List<Repository> visibleTracked(List<Repository> tracked) {
    if (currentUser.canSeePrivateRepositories()) {
      return tracked;
    }
    return tracked.stream()
        .filter(repository -> RepositoryVisibility.isPublic(repository.visibility()))
        .toList();
  }

  public record CollectionStatusResponse(Instant lastSyncAt, List<RepositoryStatus> repositories) {}

  public record RepositoryStatus(
      long id, String fullName, String collectionStatus, List<JobStatus> jobs) {}

  public record JobStatus(String jobType, String status, String errorMessage) {}
}
