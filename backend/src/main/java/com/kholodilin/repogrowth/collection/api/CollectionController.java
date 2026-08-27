package com.kholodilin.repogrowth.collection.api;

import com.kholodilin.repogrowth.collection.domain.CollectionJob;
import com.kholodilin.repogrowth.collection.domain.CollectionRun;
import com.kholodilin.repogrowth.collection.persistence.CollectionJobJdbcRepository;
import com.kholodilin.repogrowth.collection.persistence.CollectionRunJdbcRepository;
import com.kholodilin.repogrowth.collection.planner.CollectionPlanner;
import com.kholodilin.repogrowth.collection.planner.DailyPlanner;
import com.kholodilin.repogrowth.collection.planner.PlanningWindow;
import com.kholodilin.repogrowth.common.api.ApiException;
import com.kholodilin.repogrowth.repository.application.RepositoryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class CollectionController {

    private final CollectionPlanner collectionPlanner;
    private final PlanningWindow planningWindow;
    private final DailyPlanner dailyPlanner;
    private final RepositoryService repositoryService;
    private final CollectionRunJdbcRepository runRepository;
    private final CollectionJobJdbcRepository jobRepository;

    public CollectionController(
            CollectionPlanner collectionPlanner,
            PlanningWindow planningWindow,
            DailyPlanner dailyPlanner,
            RepositoryService repositoryService,
            CollectionRunJdbcRepository runRepository,
            CollectionJobJdbcRepository jobRepository
    ) {
        this.collectionPlanner = collectionPlanner;
        this.planningWindow = planningWindow;
        this.dailyPlanner = dailyPlanner;
        this.repositoryService = repositoryService;
        this.runRepository = runRepository;
        this.jobRepository = jobRepository;
    }

    @PostMapping("/repositories/{id}/collect")
    public CollectionRunResponse collect(@PathVariable long id) {
        repositoryService.get(id);
        CollectionRun run = collectionPlanner.planRepository(id, planningWindow.businessDate());
        return toResponse(run);
    }

    @PostMapping("/collection/plan")
    public DailyPlanner.PlannerResult plan() {
        return dailyPlanner.planNow(true);
    }

    @GetMapping("/collection-runs/{id}")
    public CollectionRunResponse getRun(@PathVariable long id) {
        CollectionRun run = runRepository.findById(id)
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
                jobs.stream().map(job -> new CollectionJobResponse(
                        job.id(),
                        job.jobType().name(),
                        job.status().name(),
                        job.attempt(),
                        job.errorCode(),
                        job.errorMessage(),
                        job.startedAt(),
                        job.completedAt()
                )).toList()
        );
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
            List<CollectionJobResponse> jobs
    ) {
    }

    public record CollectionJobResponse(
            long id,
            String jobType,
            String status,
            int attempt,
            String errorCode,
            String errorMessage,
            java.time.Instant startedAt,
            java.time.Instant completedAt
    ) {
    }
}
