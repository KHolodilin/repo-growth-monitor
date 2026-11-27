package com.kholodilin.repogrowth.collection.api;

import com.kholodilin.repogrowth.collection.application.CollectionRunMapper;
import com.kholodilin.repogrowth.collection.application.CollectionRunMapper.CollectionJobView;
import com.kholodilin.repogrowth.collection.application.CollectionRunMapper.CollectionRunView;
import com.kholodilin.repogrowth.collection.domain.CollectionRun;
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
  private final CollectionRunMapper runMapper;

  public CollectionController(
      PlanningWindow planningWindow,
      DailyPlanner dailyPlanner,
      CollectionRunJdbcRepository runRepository,
      CollectionRunMapper runMapper) {
    this.planningWindow = planningWindow;
    this.dailyPlanner = dailyPlanner;
    this.runRepository = runRepository;
    this.runMapper = runMapper;
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
    return toResponse(runMapper.toView(run));
  }

  /** Wire-compatible admin DTO; mapping logic lives in {@link CollectionRunMapper}. */
  public static CollectionRunResponse toResponse(CollectionRunView view) {
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
        view.jobs().stream().map(CollectionController::toJobResponse).toList());
  }

  private static CollectionJobResponse toJobResponse(CollectionJobView job) {
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
