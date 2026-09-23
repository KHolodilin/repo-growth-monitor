package com.kholodilin.repogrowth.topic.planner;

import com.kholodilin.repogrowth.common.config.SearchProperties;
import com.kholodilin.repogrowth.search.domain.SearchRunStatus;
import com.kholodilin.repogrowth.topic.application.TopicWatchSync;
import com.kholodilin.repogrowth.topic.domain.TopicWatch;
import com.kholodilin.repogrowth.topic.persistence.TopicRunJdbcRepository;
import com.kholodilin.repogrowth.topic.persistence.TopicWatchJdbcRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Slf4j
@Service
public class TopicPlanner {

    private final TopicWatchJdbcRepository watchRepository;
    private final TopicRunJdbcRepository runRepository;
    private final TopicWatchSync topicWatchSync;
    private final SearchProperties searchProperties;

    public TopicPlanner(
            TopicWatchJdbcRepository watchRepository,
            TopicRunJdbcRepository runRepository,
            TopicWatchSync topicWatchSync,
            SearchProperties searchProperties
    ) {
        this.watchRepository = watchRepository;
        this.runRepository = runRepository;
        this.topicWatchSync = topicWatchSync;
        this.searchProperties = searchProperties;
    }

    @Transactional
    public int planAll(LocalDate businessDate) {
        topicWatchSync.syncTracked();
        List<TopicWatch> watches = watchRepository.findEnabled();
        for (TopicWatch watch : watches) {
            runRepository.insertIgnore(watch.id(), watch.repositoryId(), businessDate);
        }
        int missed = runRepository.markMissedDays(businessDate, searchProperties.gapLookbackDays());
        log.info(
                "Topic planner processed watches={} missedDays={} businessDate={}",
                watches.size(),
                missed,
                businessDate
        );
        return watches.size();
    }

    @Transactional
    public long planWatch(long topicWatchId, long repositoryId, LocalDate businessDate) {
        var existing = runRepository.find(topicWatchId, businessDate);
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
        runRepository.insertIgnore(topicWatchId, repositoryId, businessDate);
        return runRepository.find(topicWatchId, businessDate).orElseThrow().id();
    }
}
