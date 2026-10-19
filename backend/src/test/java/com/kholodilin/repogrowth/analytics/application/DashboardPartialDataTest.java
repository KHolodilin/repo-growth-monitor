package com.kholodilin.repogrowth.analytics.application;

import com.kholodilin.repogrowth.analytics.api.DashboardResponse.TrafficLeader;
import com.kholodilin.repogrowth.analytics.api.DashboardResponse.TrafficPoint;
import com.kholodilin.repogrowth.traffic.persistence.TrafficJdbcRepository.RepositoryDayTotals;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardPartialDataTest {

    @Test
    void aHoleBreaksTheLineAndAPartialDayKeepsTheSumOfRepositoriesThatHaveIt() {
        LocalDate today = LocalDate.of(2026, 8, 23);
        LocalDate twentieth = LocalDate.of(2026, 8, 20);
        LocalDate twentySecond = LocalDate.of(2026, 8, 22);
        List<TrafficPoint> traffic = AnalyticsService.portfolioSeries(
                LocalDate.of(2026, 8, 18),
                today,
                today,
                3,
                Map.of(
                        twentieth, List.of(
                                new RepositoryDayTotals(twentieth, "owner/a", 10, 4, 1),
                                new RepositoryDayTotals(twentieth, "owner/b", 0, 0, 0)
                        ),
                        twentySecond, List.of(new RepositoryDayTotals(twentySecond, "owner/a", 8, 3, 1))
                )
        );
        assertThat(traffic).extracting(TrafficPoint::date).containsExactly(
                twentieth,
                LocalDate.of(2026, 8, 21),
                twentySecond
        );
        assertThat(traffic.get(0).missingRepositories()).isEqualTo(1);
        assertThat(traffic.get(0).views()).isEqualTo(10);
        assertThat(traffic.get(1).views()).isNull();
        assertThat(traffic.get(1).missingRepositories()).isEqualTo(3);
        assertThat(traffic.get(2).views()).isEqualTo(8);
        assertThat(traffic.get(2).missingRepositories()).isEqualTo(2);
        assertThat(traffic).noneMatch(point -> point.date().equals(today));
    }

    @Test
    void noStoredDaysProduceAnEmptySeries() {
        assertThat(AnalyticsService.portfolioSeries(
                LocalDate.of(2026, 8, 20),
                LocalDate.of(2026, 8, 22),
                LocalDate.of(2026, 8, 22),
                2,
                Map.of()
        )).isEmpty();
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
