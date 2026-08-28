package com.kholodilin.repogrowth.search.planner;

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

    public SearchPlanner(SearchQueryJdbcRepository queryRepository, SearchRunJdbcRepository runRepository) {
        this.queryRepository = queryRepository;
        this.runRepository = runRepository;
    }

    @Transactional
    public int planAll(LocalDate businessDate) {
        List<SearchQuery> queries = queryRepository.findEnabled();
        for (SearchQuery query : queries) {
            runRepository.insertIgnore(query.id(), query.repositoryId(), businessDate);
        }
        log.info("Search planner processed queries={} businessDate={}", queries.size(), businessDate);
        return queries.size();
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
