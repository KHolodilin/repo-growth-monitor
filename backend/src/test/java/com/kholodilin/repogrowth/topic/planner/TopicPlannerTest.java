package com.kholodilin.repogrowth.topic.planner;

import com.kholodilin.repogrowth.common.config.SearchProperties;
import com.kholodilin.repogrowth.search.domain.SearchRunStatus;
import com.kholodilin.repogrowth.topic.application.TopicWatchSync;
import com.kholodilin.repogrowth.topic.domain.TopicRun;
import com.kholodilin.repogrowth.topic.domain.TopicWatch;
import com.kholodilin.repogrowth.topic.persistence.TopicRunJdbcRepository;
import com.kholodilin.repogrowth.topic.persistence.TopicWatchJdbcRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TopicPlannerTest {

    @Mock
    TopicWatchJdbcRepository watchRepository;
    @Mock
    TopicRunJdbcRepository runRepository;
    @Mock
    TopicWatchSync topicWatchSync;

    private TopicPlanner planner;
    private TopicWatch watch;
    private LocalDate date;

    @BeforeEach
    void setUp() {
        planner = new TopicPlanner(
                watchRepository,
                runRepository,
                topicWatchSync,
                new SearchProperties(1, 50, Duration.ofHours(24), 30, new SearchProperties.Activity(30, 180))
        );
        Instant now = Instant.parse("2026-09-23T06:00:00Z");
        watch = new TopicWatch(7L, 13L, "outbox", null, "stars", "desc", true, 50, now, now);
        date = LocalDate.of(2026, 9, 23);
    }

    @Test
    void planAllSyncsWatchesAndQueuesMissingDays() {
        when(watchRepository.findEnabled()).thenReturn(List.of(watch));
        when(runRepository.markMissedDays(date, 30)).thenReturn(2);

        assertThat(planner.planAll(date)).isEqualTo(1);
        verify(topicWatchSync).syncTracked();
        verify(runRepository).insertIgnore(7L, 13L, date);
    }

    @Test
    void planWatchReusesAnInFlightRun() {
        when(runRepository.find(7L, date)).thenReturn(Optional.of(run(SearchRunStatus.RUNNING)));
        assertThat(planner.planWatch(7L, 13L, date)).isEqualTo(3L);
    }

    @Test
    void planWatchRequeuesAFinishedRun() {
        when(runRepository.find(7L, date)).thenReturn(Optional.of(run(SearchRunStatus.SUCCESS)));
        assertThat(planner.planWatch(7L, 13L, date)).isEqualTo(3L);
        verify(runRepository).requeueCompleted(3L);
    }

    @Test
    void planWatchInsertsWhenTheDayIsMissing() {
        when(runRepository.find(7L, date))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(run(SearchRunStatus.READY)));
        assertThat(planner.planWatch(7L, 13L, date)).isEqualTo(3L);
        verify(runRepository).insertIgnore(7L, 13L, date);
    }

    private TopicRun run(SearchRunStatus status) {
        Instant now = Instant.parse("2026-09-23T06:00:00Z");
        return new TopicRun(3L, 7L, 13L, date, status, 1, null, null, null, now, now, now, 10, 1, null, null);
    }
}
