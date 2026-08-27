package com.kholodilin.repogrowth.collection.planner;

import com.kholodilin.repogrowth.collection.domain.CollectionJobType;
import com.kholodilin.repogrowth.collection.domain.CollectionRun;
import com.kholodilin.repogrowth.collection.persistence.CollectionJobJdbcRepository;
import com.kholodilin.repogrowth.collection.persistence.CollectionRunJdbcRepository;
import com.kholodilin.repogrowth.repository.domain.Repository;
import com.kholodilin.repogrowth.repository.persistence.RepositoryJdbcRepository;
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

    public CollectionPlanner(
            RepositoryJdbcRepository repositoryJdbcRepository,
            CollectionRunJdbcRepository runRepository,
            CollectionJobJdbcRepository jobRepository
    ) {
        this.repositoryJdbcRepository = repositoryJdbcRepository;
        this.runRepository = runRepository;
        this.jobRepository = jobRepository;
    }

    @Transactional
    public int planAll(LocalDate businessDate) {
        List<Repository> tracked = repositoryJdbcRepository.findTracked();
        int createdRuns = 0;
        for (Repository repository : tracked) {
            planRepository(repository.id(), businessDate);
            createdRuns++;
        }
        log.info("Collection planner processed trackedRepositories={} businessDate={}", tracked.size(), businessDate);
        return createdRuns;
    }

    @Transactional
    public CollectionRun planRepository(long repositoryId, LocalDate businessDate) {
        CollectionRun run = runRepository.insertIgnore(repositoryId, businessDate, JOB_TYPES.length);
        for (CollectionJobType type : JOB_TYPES) {
            jobRepository.insertIgnore(run.id(), repositoryId, businessDate, type);
        }
        runRepository.refreshAggregates(run.id());
        return runRepository.findById(run.id()).orElse(run);
    }
}
