package com.kholodilin.repogrowth.search.application;

import com.kholodilin.repogrowth.search.domain.SearchQuery;
import com.kholodilin.repogrowth.search.domain.SearchRun;
import com.kholodilin.repogrowth.search.domain.SearchRunStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RankChangeTest {

    @Test
    void missedDaysAreNotInterpolatedAsPositions() {
        SearchQuery query = new SearchQuery(1L, 1L, "q", "outbox", true, 50, Instant.now(), Instant.now());
        SearchRun day1 = run(query.id(), LocalDate.of(2026, 8, 1), 10);
        SearchRun day10 = run(query.id(), LocalDate.of(2026, 8, 10), 7);
        Integer change = changeSince(List.of(day1, day10), day10, 7);
        assertThat(change).isEqualTo(3);
        Integer missingBaseline = changeSince(List.of(day10), day10, 7);
        assertThat(missingBaseline).isNull();
    }

    private SearchRun run(long queryId, LocalDate date, int position) {
        return new SearchRun(1L, queryId, 1L, date, SearchRunStatus.SUCCESS, 1, null, null, null, null, null, 100, position, null, null);
    }

    private Integer changeSince(List<SearchRun> runs, SearchRun latest, int days) {
        LocalDate target = latest.businessDate().minusDays(days);
        SearchRun baseline = null;
        for (SearchRun run : runs) {
            if (!run.businessDate().isAfter(target)) {
                baseline = run;
            }
        }
        if (baseline == null || baseline.trackedRepositoryPosition() == null || latest.trackedRepositoryPosition() == null) {
            return null;
        }
        return baseline.trackedRepositoryPosition() - latest.trackedRepositoryPosition();
    }
}
