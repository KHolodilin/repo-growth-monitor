package com.kholodilin.repogrowth.collection.planner;

import com.kholodilin.repogrowth.collection.domain.CollectionJobType;
import com.kholodilin.repogrowth.collection.domain.CollectionRun;
import com.kholodilin.repogrowth.collection.persistence.CollectionJobJdbcRepository;
import com.kholodilin.repogrowth.collection.persistence.CollectionRunJdbcRepository;
import com.kholodilin.repogrowth.repository.domain.Repository;
import com.kholodilin.repogrowth.repository.persistence.RepositoryJdbcRepository;
import com.kholodilin.repogrowth.traffic.persistence.TrafficJdbcRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Slf4j
@Service
public class CollectionPlanner {

    private static final CollectionJobType[] JOB_TYPES = CollectionJobType.values();

    private final RepositoryJdbcRepository repositoryJdbcRepository;
    private final CollectionRunJdbcRepository runRepository;
    private final CollectionJobJdbcRepository jobRepository;
    private final TrafficJdbcRepository trafficJdbcRepository;

    public CollectionPlanner(
            RepositoryJdbcRepository repositoryJdbcRepository,
            CollectionRunJdbcRepository runRepository,
            CollectionJobJdbcRepository jobRepository,
            TrafficJdbcRepository trafficJdbcRepository
    ) {
        this.repositoryJdbcRepository = repositoryJdbcRepository;
        this.runRepository = runRepository;
        this.jobRepository = jobRepository;
        this.trafficJdbcRepository = trafficJdbcRepository;
    }

    @Transactional
    public int planAll(LocalDate businessDate) {
        List<Repository> tracked = repositoryJdbcRepository.findTracked();
        int createdRuns = 0;
        for (Repository repository : tracked) {
            planRepository(repository.id(), businessDate, false);
            createdRuns++;
        }
        log.info("Collection planner processed trackedRepositories={} businessDate={}", tracked.size(), businessDate);
        return createdRuns;
    }

    @Transactional
    public CollectionRun planRepository(long repositoryId, LocalDate businessDate) {
        return planRepository(repositoryId, businessDate, false);
    }

    @Transactional
    public CollectionRun planRepository(long repositoryId, LocalDate businessDate, boolean refreshTraffic) {
        CollectionRun run = runRepository.insertIgnore(repositoryId, businessDate, JOB_TYPES.length);
        for (CollectionJobType type : JOB_TYPES) {
            jobRepository.insertIgnore(run.id(), repositoryId, businessDate, type);
        }
        jobRepository.requeueFailed(run.id());
        if (refreshTraffic) {
            jobRepository.requeueCompleted(run.id(), CollectionJobType.REPOSITORY_STATS);
        }
        if (refreshTraffic || isTrafficStale(repositoryId, businessDate)) {
            jobRepository.requeueCompleted(run.id(), CollectionJobType.TRAFFIC);
        }
        runRepository.refreshAggregates(run.id());
        return runRepository.findById(run.id()).orElse(run);
    }

    private boolean isTrafficStale(long repositoryId, LocalDate businessDate) {
        return trafficJdbcRepository.latestDate(repositoryId)
                .filter(latest -> !latest.isBefore(businessDate))
                .isEmpty();
    }
}
