package com.kholodilin.repogrowth.collection.api;

import com.kholodilin.repogrowth.collection.domain.CollectionJob;
import com.kholodilin.repogrowth.collection.domain.CollectionRun;
import com.kholodilin.repogrowth.collection.persistence.CollectionJobJdbcRepository;
import com.kholodilin.repogrowth.collection.persistence.CollectionRunJdbcRepository;
import com.kholodilin.repogrowth.collection.planner.DailyPlanner;
import com.kholodilin.repogrowth.collection.planner.PlanningWindow;
import com.kholodilin.repogrowth.common.api.ApiException;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
public class CollectionController {

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private com.kholodilin.repogrowth.orchestration.CollectionRequestService commands;

  private final PlanningWindow planningWindow;
  private final DailyPlanner dailyPlanner;
  private final CollectionRunJdbcRepository runRepository;
  private final CollectionJobJdbcRepository jobRepository;

  public CollectionController(
      PlanningWindow planningWindow,
      DailyPlanner dailyPlanner,
      CollectionRunJdbcRepository runRepository,
      CollectionJobJdbcRepository jobRepository) {
    this.planningWindow = planningWindow;
    this.dailyPlanner = dailyPlanner;
    this.runRepository = runRepository;
    this.jobRepository = jobRepository;
  }

  @org.springframework.web.bind.annotation.ResponseStatus(
      org.springframework.http.HttpStatus.CREATED)
  @PostMapping("/repositories/{id}/collect")
  public Object collect(@PathVariable long id) {
    if (commands == null) {
      throw ApiException.serviceUnavailable("Collector orchestration is required");
    }
    return commands.manual(id, planningWindow.businessDate());
  }

  @org.springframework.web.bind.annotation.ResponseStatus(
      org.springframework.http.HttpStatus.CREATED)
  @PostMapping("/collection/plan")
  public DailyPlanner.PlannerResult plan() {
    return dailyPlanner.planNow(true);
  }

  @GetMapping("/collection-runs/{id}")
  public CollectionRunResponse getRun(@PathVariable long id) {
    CollectionRun run =
        runRepository
            .findById(id)
            .orElseThrow(() -> ApiException.notFound("Collection run not found"));
    return toResponse(run);
  }

  public CollectionRunResponse toResponse(CollectionRun run) {
    List<CollectionJob> jobs = jobRepository.findByRun(run.id());
    return new CollectionRunResponse(
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
                new CollectionJobResponse(
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

  public record CollectionRunResponse(
      long id,
      long repositoryId,
      String businessDate,
      String status,
      int plannedJobs,
      int successfulJobs,
      int failedJobs,
      java.time.Instant createdAt,
      java.time.Instant completedAt,
      List<CollectionJobResponse> jobs) {}

  public record CollectionJobResponse(
      long id,
      String jobType,
      String status,
      int attempt,
      String errorCode,
      String errorMessage,
      java.time.Instant startedAt,
      java.time.Instant completedAt) {}
}
