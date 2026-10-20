package com.kholodilin.repogrowth.search.planner;

import com.kholodilin.repogrowth.common.config.SearchProperties;
import com.kholodilin.repogrowth.search.domain.SearchQuery;
import com.kholodilin.repogrowth.search.domain.SearchRun;
import com.kholodilin.repogrowth.search.domain.SearchRunStatus;
import com.kholodilin.repogrowth.search.persistence.SearchQueryJdbcRepository;
import com.kholodilin.repogrowth.search.persistence.SearchRunJdbcRepository;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchPlannerTest {

    @Mock
    SearchQueryJdbcRepository queryRepository;
    @Mock
    SearchRunJdbcRepository runRepository;

    private SearchPlanner planner;
    private SearchQuery query;
    private LocalDate date;

    @BeforeEach
    void setUp() {
        planner = new SearchPlanner(
                queryRepository,
                runRepository,
                new SearchProperties(1, 50, Duration.ofHours(24), 30, new SearchProperties.Activity(30, 180))
        );
        Instant now = Instant.parse("2026-10-20T06:00:00Z");
        query = new SearchQuery(5L, 13L, "outbox", "outbox", true, 50, now, now);
        date = LocalDate.of(2026, 10, 20);
    }

    @Test
    void planAllLeavesASuccessfulQueryUntilTheNextSnapshot() {
        when(queryRepository.findEnabled()).thenReturn(List.of(query));
        when(runRepository.find(5L, date)).thenReturn(Optional.of(run(SearchRunStatus.SUCCESS)));
        when(runRepository.markMissedDays(date, 30)).thenReturn(0);

        assertThat(planner.planAll(date)).isEqualTo(1);

        verify(runRepository).insertIgnore(5L, 13L, date);
        verify(runRepository, never()).requeueCompleted(9L);
    }

    @Test
    void planAllLeavesAnInFlightQueryWhenTheSnapshotIsDue() {
        when(queryRepository.findEnabled()).thenReturn(List.of(query));
        when(runRepository.find(5L, date)).thenReturn(Optional.of(run(SearchRunStatus.READY)));
        when(runRepository.markMissedDays(date, 30)).thenReturn(0);

        planner.planAll(date, true);

        verify(runRepository, never()).requeueCompleted(9L);
    }

    @Test
    void planAllRequeuesASuccessfulQueryOnASnapshot() {
        when(queryRepository.findEnabled()).thenReturn(List.of(query));
        when(runRepository.find(5L, date)).thenReturn(Optional.of(run(SearchRunStatus.SUCCESS)));
        when(runRepository.markMissedDays(date, 30)).thenReturn(0);

        planner.planAll(date, true);

        verify(runRepository).requeueCompleted(9L);
    }

    @Test
    void planAllRequeuesAFailedQueryWithoutWaitingForASnapshot() {
        when(queryRepository.findEnabled()).thenReturn(List.of(query));
        when(runRepository.find(5L, date)).thenReturn(Optional.of(run(SearchRunStatus.FAILED)));
        when(runRepository.markMissedDays(date, 30)).thenReturn(0);

        planner.planAll(date, false);

        verify(runRepository).requeueCompleted(9L);
    }

    @Test
    void planAllSkipsAMissingRunRow() {
        when(queryRepository.findEnabled()).thenReturn(List.of(query));
        when(runRepository.find(5L, date)).thenReturn(Optional.empty());
        when(runRepository.markMissedDays(date, 30)).thenReturn(0);

        planner.planAll(date, true);

        verify(runRepository, never()).requeueCompleted(9L);
    }

    @Test
    void planRepositoryQueuesEnabledQueriesOnly() {
        Instant now = Instant.parse("2026-10-20T06:00:00Z");
        SearchQuery disabled = new SearchQuery(6L, 13L, "off", "off", false, 50, now, now);
        when(queryRepository.findByRepository(13L)).thenReturn(List.of(query, disabled));
        when(runRepository.find(5L, date))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(run(SearchRunStatus.READY)));

        assertThat(planner.planRepository(13L, date)).isEqualTo(1);
        verify(runRepository).insertIgnore(5L, 13L, date);
    }

    private SearchRun run(SearchRunStatus status) {
        Instant now = Instant.parse("2026-10-20T06:00:00Z");
        return new SearchRun(
                9L, 5L, 13L, date, status, 1,
                null, null, null, now, now, now, 80, 4, "SUCCESS", null, null
        );
    }
}
