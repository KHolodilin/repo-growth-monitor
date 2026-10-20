package com.kholodilin.repogrowth.search.planner;

import com.kholodilin.repogrowth.common.config.SearchProperties;
import com.kholodilin.repogrowth.search.domain.SearchQuery;
import com.kholodilin.repogrowth.search.domain.SearchRunStatus;
import com.kholodilin.repogrowth.search.persistence.SearchQueryJdbcRepository;
import com.kholodilin.repogrowth.search.persistence.SearchRunJdbcRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Slf4j
@Service
public class SearchPlanner {

    private final SearchQueryJdbcRepository queryRepository;
    private final SearchRunJdbcRepository runRepository;
    private final SearchProperties searchProperties;

    public SearchPlanner(
            SearchQueryJdbcRepository queryRepository,
            SearchRunJdbcRepository runRepository,
            SearchProperties searchProperties
    ) {
        this.queryRepository = queryRepository;
        this.runRepository = runRepository;
        this.searchProperties = searchProperties;
    }

    @Transactional
    public int planAll(LocalDate businessDate) {
        return planAll(businessDate, false);
    }

    @Transactional
    public int planAll(LocalDate businessDate, boolean snapshotDue) {
        List<SearchQuery> queries = queryRepository.findEnabled();
        for (SearchQuery query : queries) {
            runRepository.insertIgnore(query.id(), query.repositoryId(), businessDate);
            requeueIfDue(query.id(), businessDate, snapshotDue);
        }
        int missed = runRepository.markMissedDays(businessDate, searchProperties.gapLookbackDays());
        log.info(
                "Search planner processed queries={} missedDays={} snapshotDue={} businessDate={}",
                queries.size(),
                missed,
                snapshotDue,
                businessDate
        );
        return queries.size();
    }

    @Transactional
    public int planRepository(long repositoryId, LocalDate businessDate) {
        int queued = 0;
        for (SearchQuery query : queryRepository.findByRepository(repositoryId)) {
            if (!query.enabled()) {
                continue;
            }
            planQuery(query.id(), query.repositoryId(), businessDate);
            queued++;
        }
        return queued;
    }

    private void requeueIfDue(long searchQueryId, LocalDate businessDate, boolean snapshotDue) {
        runRepository.find(searchQueryId, businessDate).ifPresent(run -> {
            boolean failed = run.status() == SearchRunStatus.FAILED;
            boolean finishedSnapshot = snapshotDue && run.status() == SearchRunStatus.SUCCESS;
            if (failed || finishedSnapshot) {
                runRepository.requeueCompleted(run.id());
            }
        });
    }

    @Transactional
    public long planQuery(long searchQueryId, long repositoryId, LocalDate businessDate) {
        var existing = runRepository.find(searchQueryId, businessDate);
        if (existing.isPresent()) {
            var run = existing.get();
            if (run.status() == SearchRunStatus.READY
                    || run.status() == SearchRunStatus.RUNNING
                    || run.status() == SearchRunStatus.RETRY) {
                return run.id();
            }
            runRepository.requeueCompleted(run.id());
            return run.id();
        }
        runRepository.insertIgnore(searchQueryId, repositoryId, businessDate);
        return runRepository.find(searchQueryId, businessDate).orElseThrow().id();
    }
}
