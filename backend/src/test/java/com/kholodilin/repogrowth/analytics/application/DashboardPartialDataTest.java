package com.kholodilin.repogrowth.analytics.application;

import com.kholodilin.repogrowth.analytics.api.DashboardResponse.TrafficLeader;
import com.kholodilin.repogrowth.analytics.api.DashboardResponse.TrafficPoint;
import com.kholodilin.repogrowth.traffic.persistence.TrafficJdbcRepository.RepositoryDayTotals;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardPartialDataTest {

    @Test
    void internalGapsAreReportedWithoutFillingZeros() {
        List<TrafficPoint> traffic = List.of(
                new TrafficPoint(LocalDate.of(2026, 8, 20), 100L, 40L, 5L),
                new TrafficPoint(LocalDate.of(2026, 8, 21), null, null, null),
                new TrafficPoint(LocalDate.of(2026, 8, 22), 130L, 50L, 8L)
        );
        var partial = AnalyticsService.partialData(traffic);
        assertThat(partial).isNotNull();
        assertThat(partial.present()).isTrue();
        assertThat(partial.message()).isEqualTo(
                "Data is unavailable for Aug 21 because the service was not running."
        );
    }

    @Test
    void calendarHolesBetweenObservedDaysArePartialEvenWithoutPlaceholderPoints() {
        List<TrafficPoint> traffic = List.of(
                new TrafficPoint(LocalDate.of(2026, 8, 20), 100L, 40L, 5L),
                new TrafficPoint(LocalDate.of(2026, 8, 22), 130L, 50L, 8L)
        );
        var partial = AnalyticsService.partialData(traffic);
        assertThat(partial).isNotNull();
        assertThat(partial.message()).isEqualTo(
                "Data is unavailable for Aug 21 because the service was not running."
        );
    }

    @Test
    void leadingEmptyDaysBeforeFirstObservationAreNotPartial() {
        List<TrafficPoint> traffic = List.of(
                new TrafficPoint(LocalDate.of(2026, 8, 20), null, null, null),
                new TrafficPoint(LocalDate.of(2026, 8, 21), 10L, 4L, 1L),
                new TrafficPoint(LocalDate.of(2026, 8, 22), 12L, 5L, 1L)
        );
        assertThat(AnalyticsService.partialData(traffic)).isNull();
    }

    @Test
    void consecutiveGapsAreFormattedAsARange() {
        assertThat(AnalyticsService.formatGapRanges(List.of(
                LocalDate.of(2026, 8, 21),
                LocalDate.of(2026, 8, 22)
        ))).isEqualTo("Aug 21–22");
    }

    @Test
    void trailingUnpublishedDaysAreDroppedFromTheSeries() {
        List<TrafficPoint> traffic = List.of(
                new TrafficPoint(LocalDate.of(2026, 8, 26), 100L, 40L, 5L),
                new TrafficPoint(LocalDate.of(2026, 8, 27), null, null, null),
                new TrafficPoint(LocalDate.of(2026, 8, 28), null, null, null)
        );
        List<TrafficPoint> trimmed = AnalyticsService.dropTrailingGaps(traffic, TrafficPoint::views);
        assertThat(trimmed).extracting(TrafficPoint::date)
                .containsExactly(LocalDate.of(2026, 8, 26));
    }

    @Test
    void namesTheRepositoryWithTheLargestShareOfADay() {
        LocalDate day = LocalDate.of(2026, 10, 8);
        List<RepositoryDayTotals> rows = List.of(
                new RepositoryDayTotals(day, "owner/quiet", 0, 0, 0),
                new RepositoryDayTotals(day, "owner/zeta", 10, 1, 0),
                new RepositoryDayTotals(day, "owner/tiny", 4, 0, 0),
                new RepositoryDayTotals(day, "owner/outbox", 35, 0, 4),
                new RepositoryDayTotals(day, "owner/alpha", 35, 9, 4),
                new RepositoryDayTotals(day, "owner/beta", 35, 2, 1)
        );
        assertThat(AnalyticsService.topShares(rows, RepositoryDayTotals::views)).containsExactly(
                new TrafficLeader("owner/alpha", 35),
                new TrafficLeader("owner/beta", 35),
                new TrafficLeader("owner/outbox", 35)
        );
        assertThat(AnalyticsService.topShares(rows, RepositoryDayTotals::uniqueVisitors)).containsExactly(
                new TrafficLeader("owner/alpha", 9),
                new TrafficLeader("owner/beta", 2),
                new TrafficLeader("owner/zeta", 1)
        );
        assertThat(AnalyticsService.topShares(rows, RepositoryDayTotals::clones)).containsExactly(
                new TrafficLeader("owner/alpha", 4),
                new TrafficLeader("owner/outbox", 4),
                new TrafficLeader("owner/beta", 1)
        );
        assertThat(AnalyticsService.topShares(List.of(), RepositoryDayTotals::views)).isEmpty();
        assertThat(AnalyticsService.topShares(
                List.of(new RepositoryDayTotals(day, "owner/quiet", 0, 0, 0)),
                RepositoryDayTotals::views
        )).isEmpty();
    }
}
