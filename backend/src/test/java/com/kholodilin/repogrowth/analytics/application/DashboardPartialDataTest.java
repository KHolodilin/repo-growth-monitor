package com.kholodilin.repogrowth.analytics.application;

import com.kholodilin.repogrowth.analytics.api.DashboardResponse;
import com.kholodilin.repogrowth.analytics.api.DashboardResponse.PortfolioStats;
import com.kholodilin.repogrowth.analytics.api.DashboardResponse.StatsPoint;
import com.kholodilin.repogrowth.analytics.api.DashboardResponse.TrafficLeader;
import com.kholodilin.repogrowth.analytics.api.DashboardResponse.TrafficPoint;
import com.kholodilin.repogrowth.traffic.persistence.TrafficJdbcRepository.RepositoryDayTotals;
import com.kholodilin.repogrowth.traffic.persistence.TrafficJdbcRepository.RepositoryStatDay;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
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
    void daysOutsideThePublishedRangeAreLeftOutAndTheMissingCountCannotGoBelowZero() {
        LocalDate today = LocalDate.of(2026, 10, 19);
        LocalDate from = LocalDate.of(2026, 10, 13);
        LocalDate to = LocalDate.of(2026, 10, 17);
        LocalDate inside = LocalDate.of(2026, 10, 15);
        LocalDate middle = LocalDate.of(2026, 10, 16);
        LocalDate later = LocalDate.of(2026, 10, 17);
        LocalDate before = LocalDate.of(2026, 10, 12);
        LocalDate after = LocalDate.of(2026, 10, 18);
        LocalDate empty = LocalDate.of(2026, 10, 14);
        Map<LocalDate, List<RepositoryDayTotals>> byDate = new LinkedHashMap<>();
        byDate.put(middle, List.of(new RepositoryDayTotals(middle, "owner/a", 3, 1, 1)));
        byDate.put(inside, List.of(
                new RepositoryDayTotals(inside, "owner/a", 2, 1, 0),
                new RepositoryDayTotals(inside, "owner/b", 1, 1, 0)
        ));
        byDate.put(later, List.of(new RepositoryDayTotals(later, "owner/a", 4, 1, 1)));
        byDate.put(before, List.of(new RepositoryDayTotals(before, "owner/a", 9, 1, 1)));
        byDate.put(after, List.of(new RepositoryDayTotals(after, "owner/a", 9, 1, 1)));
        byDate.put(empty, List.of());

        List<TrafficPoint> traffic = AnalyticsService.portfolioSeries(from, to, today, 1, byDate);

        assertThat(traffic).extracting(TrafficPoint::date).containsExactly(inside, middle, later);
        assertThat(traffic.get(0).missingRepositories()).isZero();
        assertThat(traffic.get(0).views()).isEqualTo(3);
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

    @Test
    void portfolioStatsSumsSnapshotsAndLeavesHolesEmpty() {
        LocalDate today = LocalDate.of(2026, 8, 23);
        LocalDate first = LocalDate.of(2026, 8, 20);
        LocalDate hole = LocalDate.of(2026, 8, 21);
        LocalDate partial = LocalDate.of(2026, 8, 22);
        List<StatsPoint> points = AnalyticsService.portfolioStatsSeries(
                LocalDate.of(2026, 8, 18),
                today,
                today,
                2,
                Map.of(
                        LocalDate.of(2026, 8, 1), List.of(stat(1, LocalDate.of(2026, 8, 1), 1, 1, 1, 1)),
                        LocalDate.of(2026, 8, 19), List.of(),
                        first, List.of(
                                stat(1, first, 100, 2, 4, 0),
                                stat(2, first, 50, 1, 1, 0)
                        ),
                        partial, List.of(stat(1, partial, 106, 2, 4, 4)),
                        today, List.of(stat(1, today, 999, 9, 9, 9))
                )
        );
        assertThat(AnalyticsService.portfolioStatsSeries(first, today, today, 2, Map.of())).isEmpty();
        Map<LocalDate, List<RepositoryStatDay>> laterFirst = new LinkedHashMap<>();
        laterFirst.put(partial, List.of(stat(1, partial, 106, 2, 4, 4)));
        laterFirst.put(first, List.of(stat(1, first, 100, 2, 4, 0), stat(2, first, 50, 1, 1, 0)));
        assertThat(AnalyticsService.portfolioStatsSeries(LocalDate.of(2026, 8, 18), hole, today, 2, laterFirst))
                .extracting(StatsPoint::date)
                .containsExactly(first);
        assertThat(AnalyticsService.portfolioStatsSeries(LocalDate.of(2026, 8, 18), today, today, 2, laterFirst))
                .extracting(StatsPoint::date)
                .containsExactly(first, hole, partial);
        assertThat(points).extracting(StatsPoint::date).containsExactly(first, hole, partial);
        assertThat(points.get(0).stars()).isEqualTo(150);
        assertThat(points.get(0).forks()).isEqualTo(3);
        assertThat(points.get(0).watchers()).isEqualTo(5);
        assertThat(points.get(0).contributors()).isNull();
        assertThat(points.get(0).missingRepositories()).isZero();
        assertThat(points.get(0).starsLeaders()).containsExactly(
                new TrafficLeader("owner/1", 100),
                new TrafficLeader("owner/2", 50)
        );
        assertThat(points.get(0).forksLeaders()).containsExactly(
                new TrafficLeader("owner/1", 2),
                new TrafficLeader("owner/2", 1)
        );
        assertThat(points.get(0).watchersLeaders()).containsExactly(
                new TrafficLeader("owner/1", 4),
                new TrafficLeader("owner/2", 1)
        );
        assertThat(points.get(0).contributorsLeaders()).isEmpty();
        assertThat(points.get(1).stars()).isNull();
        assertThat(points.get(1).missingRepositories()).isEqualTo(2);
        assertThat(points.get(2).stars()).isEqualTo(106);
        assertThat(points.get(2).missingRepositories()).isEqualTo(1);
        assertThat(points.get(2).starsLeaders()).containsExactly(new TrafficLeader("owner/1", 106));
        assertThat(points.get(2).contributorsLeaders()).isEmpty();
        assertThat(AnalyticsService.topStatShares(List.of(), RepositoryStatDay::stars)).isEmpty();
        assertThat(AnalyticsService.topStatShares(
                List.of(stat(1, first, 0, 0, 0, 0)),
                RepositoryStatDay::stars
        )).isEmpty();

        PortfolioStats stats = AnalyticsService.portfolioStats(
                List.of(stat(1, partial, 106, 2, 4, 4), stat(2, first, 50, 1, 1, 1)),
                false,
                points
        );
        assertThat(stats.totals().stars()).isEqualTo(new DashboardResponse.StatsTotal(156,  -44, -29.3));
        assertThat(stats.totals().forks()).isEqualTo(new DashboardResponse.StatsTotal(3, -1, -33.3));
        assertThat(stats.totals().watchers()).isEqualTo(new DashboardResponse.StatsTotal(5, -1, -20.0));
        assertThat(stats.totals().contributors()).isEqualTo(new DashboardResponse.StatsTotal(null, null, null));

        PortfolioStats all = AnalyticsService.portfolioStats(List.of(), true, points);
        assertThat(all.totals().stars().value()).isNull();
        assertThat(all.totals().stars().absoluteChange()).isNull();
        assertThat(all.totals().contributors().growthPercent()).isNull();

        assertThat(AnalyticsService.portfolioStats(List.of(stat(1, first, 10, 0, 0, 0)), false, List.of(points.get(0)))
                .totals().stars().absoluteChange()).isNull();
    }

    @Test
    void uniqueContributorsUnionLoginsAcrossRepositoriesAndIgnoreUncollectedDays() {
        LocalDate day = LocalDate.of(2026, 8, 20);
        LocalDate next = LocalDate.of(2026, 8, 22);
        LocalDate today = LocalDate.of(2026, 8, 23);
        RepositoryStatDay left = new RepositoryStatDay(1, "owner/1", day, 10, 1, 1, 9, List.of("alice", "bob"));
        RepositoryStatDay right = new RepositoryStatDay(2, "owner/2", day, 5, 1, 1, 9, List.of("bob", "carol"));
        RepositoryStatDay later = new RepositoryStatDay(1, "owner/1", next, 12, 1, 1, 9, List.of("alice"));
        List<StatsPoint> points = AnalyticsService.portfolioStatsSeries(
                day, today, today, 2,
                Map.of(day, List.of(left, right), next, List.of(later))
        );
        assertThat(points.get(0).contributors()).isEqualTo(3);
        assertThat(points.get(0).contributorsLeaders()).containsExactly(
                new TrafficLeader("owner/1", 2),
                new TrafficLeader("owner/2", 2)
        );
        assertThat(points.get(1).contributors()).isNull();
        assertThat(points.get(2).contributors()).isEqualTo(1);
        PortfolioStats stats = AnalyticsService.portfolioStats(List.of(later, right), false, points);
        assertThat(stats.totals().contributors().value()).isEqualTo(3);
        assertThat(stats.totals().contributors().absoluteChange()).isEqualTo(-2);
        assertThat(AnalyticsService.uniqueContributorCount(List.of(stat(1, day, 1, 0, 0, 1)))).isNull();
        assertThat(AnalyticsService.uniqueContributorCount(List.of(
                new RepositoryStatDay(1, "owner/1", day, 1, 0, 0, 0, List.of())
        ))).isZero();
    }

    private static RepositoryStatDay stat(long id, LocalDate date, int stars, int forks, int watchers, int contributors) {
        return new RepositoryStatDay(id, "owner/" + id, date, stars, forks, watchers, contributors);
    }
}
