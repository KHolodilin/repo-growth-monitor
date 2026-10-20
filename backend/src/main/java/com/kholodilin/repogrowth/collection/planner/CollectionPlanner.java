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
    private static final CollectionJobType[] SNAPSHOT_JOBS = {
            CollectionJobType.REFERRERS,
            CollectionJobType.POPULAR_PATHS,
            CollectionJobType.REPOSITORY_STATS,
            CollectionJobType.GROWTH_EVENTS
    };

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
        return planAll(businessDate, false);
    }

    @Transactional
    public int planAll(LocalDate businessDate, boolean snapshotDue) {
        List<Repository> tracked = repositoryJdbcRepository.findTracked();
        int createdRuns = 0;
        for (Repository repository : tracked) {
            planRepository(repository.id(), businessDate, false, snapshotDue);
            createdRuns++;
        }
        log.info(
                "Collection planner processed trackedRepositories={} snapshotDue={} businessDate={}",
                tracked.size(),
                snapshotDue,
                businessDate
        );
        return createdRuns;
    }

    @Transactional
    public CollectionRun planRepository(long repositoryId, LocalDate businessDate) {
        return planRepository(repositoryId, businessDate, false, false);
    }

    /**
     * Collect now recollects everything for the day, including the referrer and path snapshots. A
     * repeated snapshot is stored under its own timestamp and both the traffic cards and the history
     * read the latest snapshot of a day, so the day keeps one column with fresher numbers.
     */
    @Transactional
    public CollectionRun planRepository(long repositoryId, LocalDate businessDate, boolean recollect) {
        return planRepository(repositoryId, businessDate, recollect, false);
    }

    @Transactional
    public CollectionRun planRepository(
            long repositoryId,
            LocalDate businessDate,
            boolean recollect,
            boolean snapshotDue
    ) {
        CollectionRun run = runRepository.insertIgnore(repositoryId, businessDate, JOB_TYPES.length);
        for (CollectionJobType type : JOB_TYPES) {
            jobRepository.insertIgnore(run.id(), repositoryId, businessDate, type);
        }
        jobRepository.requeueFailed(run.id());
        if (recollect) {
            for (CollectionJobType type : JOB_TYPES) {
                jobRepository.requeueCompleted(run.id(), type);
            }
        } else {
            if (isTrafficStale(repositoryId, businessDate)) {
                jobRepository.requeueCompleted(run.id(), CollectionJobType.TRAFFIC);
            }
            if (snapshotDue) {
                for (CollectionJobType type : SNAPSHOT_JOBS) {
                    jobRepository.requeueCompleted(run.id(), type);
                }
            }
        }
        runRepository.refreshAggregates(run.id());
        return runRepository.findById(run.id()).orElse(run);
    }

    private boolean isTrafficStale(long repositoryId, LocalDate businessDate) {
        LocalDate yesterday = businessDate.minusDays(1);
        return trafficJdbcRepository.latestDate(repositoryId)
                .filter(latest -> !latest.isBefore(yesterday))
                .isEmpty();
    }
}
