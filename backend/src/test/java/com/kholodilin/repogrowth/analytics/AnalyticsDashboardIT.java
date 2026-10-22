package com.kholodilin.repogrowth.analytics;

import com.kholodilin.repogrowth.analytics.api.DashboardResponse;
import com.kholodilin.repogrowth.analytics.api.DashboardResponse.DashboardState;
import com.kholodilin.repogrowth.analytics.application.AnalyticsService;
import com.kholodilin.repogrowth.collection.domain.CollectionJobType;
import com.kholodilin.repogrowth.collection.domain.CollectionRun;
import com.kholodilin.repogrowth.collection.persistence.CollectionJobJdbcRepository;
import com.kholodilin.repogrowth.collection.persistence.CollectionRunJdbcRepository;
import com.kholodilin.repogrowth.common.api.ApiException;
import com.kholodilin.repogrowth.repository.domain.GitHubOwner;
import com.kholodilin.repogrowth.repository.domain.OwnerType;
import com.kholodilin.repogrowth.repository.domain.Repository;
import com.kholodilin.repogrowth.repository.persistence.GitHubOwnerJdbcRepository;
import com.kholodilin.repogrowth.event.persistence.GrowthEventJdbcRepository;
import com.kholodilin.repogrowth.repository.persistence.RepositoryJdbcRepository;
import com.kholodilin.repogrowth.search.persistence.SearchQueryJdbcRepository;
import com.kholodilin.repogrowth.search.persistence.SearchRunJdbcRepository;
import com.kholodilin.repogrowth.support.AbstractPostgresTest;
import com.kholodilin.repogrowth.traffic.persistence.TrafficJdbcRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnalyticsDashboardIT extends AbstractPostgresTest {

    @Autowired
    AnalyticsService analyticsService;
    @Autowired
    GitHubOwnerJdbcRepository ownerJdbcRepository;
    @Autowired
    RepositoryJdbcRepository repositoryJdbcRepository;
    @Autowired
    TrafficJdbcRepository trafficJdbcRepository;
    @Autowired
    CollectionRunJdbcRepository runRepository;
    @Autowired
    CollectionJobJdbcRepository jobRepository;
    @Autowired
    JdbcClient jdbcClient;
    @Autowired
    Clock clock;
    @Autowired
    SearchQueryJdbcRepository searchQueryJdbcRepository;
    @Autowired
    SearchRunJdbcRepository searchRunJdbcRepository;
    @Autowired
    GrowthEventJdbcRepository growthEventJdbcRepository;

    private Repository kafka;
    private Repository outbox;

    @BeforeEach
    void seed() {
        wipeRepositoryData(jdbcClient);
    }

    @Test
    void noTrackedRepositoriesReturnsEmptyState() {
        DashboardResponse dashboard = analyticsService.dashboard("30d");
        assertThat(dashboard.state()).isEqualTo(DashboardState.NO_REPOSITORIES);
        assertThat(dashboard.summary().repositories()).isZero();
        assertThat(dashboard.traffic()).isEmpty();
        assertThat(dashboard.stats().points()).isEmpty();
        assertThat(dashboard.stats().totals().stars().value()).isNull();
        assertThat(dashboard.repositories()).isEmpty();
        assertThat(dashboard.highlights()).isEqualTo(DashboardResponse.Highlights.none());
    }

    @Test
    void trackedRepositoriesWithoutTrafficReturnFirstCollectionState() {
        createRepos();
        DashboardResponse dashboard = analyticsService.dashboard("30d");
        assertThat(dashboard.state()).isEqualTo(DashboardState.FIRST_COLLECTION);
        assertThat(dashboard.summary().repositories()).isEqualTo(2);
        assertThat(dashboard.summary().views().value()).isZero();
        assertThat(dashboard.traffic()).isEmpty();
        assertThat(dashboard.stats().points()).isEmpty();
        assertThat(dashboard.stats().totals().stars().value()).isNull();
        assertThat(dashboard.repositories()).hasSize(2);
        assertThat(dashboard.repositories()).extracting(DashboardResponse.RepositoryRow::fullName)
                .containsExactlyInAnyOrder("acme/kafka-starter", "acme/spring-outbox");
        assertThat(dashboard.repositories()).allMatch(row -> row.forks() == 2);
    }

    @Test
    void dashboardComputesGrowthGapsAndPartialCollection() {
        createRepos();
        LocalDate today = LocalDate.now(clock);
        trafficJdbcRepository.upsertDaily(kafka.id(), today.minusDays(8), 100, 50, 10, 8);
        trafficJdbcRepository.upsertDaily(kafka.id(), today.minusDays(6), 120, 60, 12, 9);
        trafficJdbcRepository.upsertDaily(kafka.id(), today.minusDays(4), 80, 40, 8, 6);
        trafficJdbcRepository.upsertDaily(outbox.id(), today.minusDays(8), 40, 20, 4, 3);
        trafficJdbcRepository.upsertDaily(outbox.id(), today.minusDays(6), 50, 25, 5, 4);
        trafficJdbcRepository.upsertDaily(outbox.id(), today.minusDays(4), 30, 15, 3, 2);

        trafficJdbcRepository.upsertDailyStats(kafka.id(), today.minusDays(10), 35, 8, 2, 0, 0);
        trafficJdbcRepository.upsertDailyStats(outbox.id(), today.minusDays(10), 120, 20, 4, 1, 0);
        repositoryJdbcRepository.upsertHealth(kafka.id(), new com.kholodilin.repogrowth.repository.domain.RepositoryHealthFacts(
                "https://example.com", true, true, true, true, true, true, true, true, true, true
        ));

        CollectionRun kafkaRun = runRepository.insertIgnore(kafka.id(), today, 4);
        jobRepository.insertIgnore(kafkaRun.id(), kafka.id(), today, CollectionJobType.TRAFFIC);
        jobRepository.insertIgnore(kafkaRun.id(), kafka.id(), today, CollectionJobType.REFERRERS);
        jobRepository.insertIgnore(kafkaRun.id(), kafka.id(), today, CollectionJobType.POPULAR_PATHS);
        jobRepository.insertIgnore(kafkaRun.id(), kafka.id(), today, CollectionJobType.REPOSITORY_STATS);
        jobRepository.findByRun(kafkaRun.id()).forEach(job -> {
            if (job.jobType() == CollectionJobType.POPULAR_PATHS) {
                jobRepository.markFailed(job.id(), "GITHUB_ERROR", "boom");
            } else {
                jobRepository.markSuccess(job.id());
            }
        });
        runRepository.refreshAggregates(kafkaRun.id());

        CollectionRun outboxRun = runRepository.insertIgnore(outbox.id(), today, 4);
        for (CollectionJobType type : CollectionJobType.values()) {
            jobRepository.insertIgnore(outboxRun.id(), outbox.id(), today, type);
        }
        jobRepository.findByRun(outboxRun.id()).forEach(job -> jobRepository.markSuccess(job.id()));
        runRepository.refreshAggregates(outboxRun.id());

        DashboardResponse dashboard = analyticsService.dashboard("7d");
        assertThat(dashboard.state()).isEqualTo(DashboardState.READY);
        assertThat(dashboard.period()).isEqualTo("7d");
        assertThat(dashboard.summary().repositories()).isEqualTo(2);
        assertThat(dashboard.summary().visitors().value()).isEqualTo(140);
        assertThat(dashboard.summary().visitors().growthPercent()).isEqualTo(100.0);
        assertThat(dashboard.summary().views().value()).isEqualTo(280);
        assertThat(dashboard.summary().clones().value()).isEqualTo(28);
        assertThat(dashboard.summary().stars().total()).isEqualTo(169);
        assertThat(dashboard.summary().stars().change()).isEqualTo(14);
        assertThat(dashboard.stats().totals().stars().value()).isEqualTo(155);
        assertThat(dashboard.stats().totals().stars().absoluteChange()).isNull();
        assertThat(dashboard.stats().points()).isEmpty();
        assertThat(dashboard.partialData()).isNull();
        assertThat(dashboard.collectionWarning()).isNotNull();
        assertThat(dashboard.collectionWarning().partialRepositories()).isEqualTo(1);

        assertThat(dashboard.traffic()).extracting(DashboardResponse.TrafficPoint::date)
                .containsExactly(today.minusDays(6), today.minusDays(5), today.minusDays(4));
        assertThat(dashboard.traffic().get(1).views()).isNull();
        assertThat(dashboard.traffic().get(1).missingRepositories()).isEqualTo(2);
        assertThat(dashboard.traffic().get(0).missingRepositories()).isZero();
        DashboardResponse.TrafficPoint firstDay = dashboard.traffic().getFirst();
        assertThat(firstDay.views()).isEqualTo(170);
        assertThat(firstDay.visitors()).isEqualTo(85);
        assertThat(firstDay.clones()).isEqualTo(17);
        assertThat(firstDay.viewsLeaders()).containsExactly(
                new DashboardResponse.TrafficLeader("acme/kafka-starter", 120),
                new DashboardResponse.TrafficLeader("acme/spring-outbox", 50)
        );
        assertThat(firstDay.visitorsLeaders()).containsExactly(
                new DashboardResponse.TrafficLeader("acme/kafka-starter", 60),
                new DashboardResponse.TrafficLeader("acme/spring-outbox", 25)
        );
        assertThat(firstDay.clonesLeaders()).containsExactly(
                new DashboardResponse.TrafficLeader("acme/kafka-starter", 12),
                new DashboardResponse.TrafficLeader("acme/spring-outbox", 5)
        );
        assertThat(dashboard.traffic()).noneMatch(point -> point.date().equals(today));

        DashboardResponse.RepositoryRow kafkaRow = dashboard.repositories().stream()
                .filter(row -> row.fullName().equals("acme/kafka-starter"))
                .findFirst()
                .orElseThrow();
        assertThat(kafkaRow.visitors()).isEqualTo(100);
        assertThat(kafkaRow.growthPercent()).isEqualTo(100.0);
        assertThat(kafkaRow.forks()).isEqualTo(2);
        assertThat(kafkaRow.discoverabilityPassed()).isEqualTo(8);
        assertThat(kafkaRow.discoverabilityTotal()).isEqualTo(13);
        assertThat(kafkaRow.trafficNotice()).isNotNull();
        assertThat(kafkaRow.trafficNotice().reason()).isEqualTo("GITHUB_DATA_MISSING");
        assertThat(kafkaRow.trafficNotice().date()).isEqualTo(today.minusDays(1));
        assertThat(kafkaRow.trafficNotice().lastTrafficDate()).isEqualTo(today.minusDays(4));
        assertThat(dashboard.highlights().visitors().fullName()).isEqualTo("acme/kafka-starter");
        assertThat(dashboard.highlights().visitors().growthPercent()).isEqualTo(100.0);
        assertThat(dashboard.highlights().rankRise()).isNull();
        assertThat(dashboard.highlights().events()).isNull();
        assertThat(kafkaRow.collectionStatus()).isEqualTo("PARTIAL");
        assertThat(dashboard.repositories().stream().filter(row -> row.fullName().equals("acme/spring-outbox")).findFirst().orElseThrow().discoverabilityPassed()).isNull();
        assertThat(dashboard.repositories().stream().filter(row -> row.fullName().equals("acme/spring-outbox")).findFirst().orElseThrow().discoverabilityTotal()).isNull();
        assertThat(kafkaRow.jobs()).extracting(DashboardResponse.JobStatus::jobType)
                .contains("TRAFFIC", "POPULAR_PATHS");

        DashboardResponse all = analyticsService.dashboard("all");
        assertThat(all.summary().views().growthPercent()).isNull();
        assertThat(all.summary().stars().change()).isNull();
    }

    @Test
    void trafficCardsShowTheLatestSnapshotForEveryPeriod() {
        createRepos();
        LocalDate today = LocalDate.now(clock);
        Instant before = today.minusDays(3).atTime(12, 0).atZone(clock.getZone()).toInstant();
        Instant latest = today.atTime(12, 0).atZone(clock.getZone()).toInstant();

        trafficJdbcRepository.upsertDaily(kafka.id(), today.minusDays(4), 37, 2, 0, 0);
        trafficJdbcRepository.upsertDaily(kafka.id(), today.minusDays(1), 6, 1, 0, 0);

        LocalDate beforeDate = today.minusDays(3);
        trafficJdbcRepository.insertReferrers(kafka.id(), beforeDate, before, "github.com", 40, 2);
        trafficJdbcRepository.insertReferrers(kafka.id(), today, latest, "github.com", 81, 3);
        trafficJdbcRepository.insertReferrers(kafka.id(), today, latest, "mvnrepository.com", 1, 1);
        trafficJdbcRepository.insertPath(kafka.id(), beforeDate, before, "/readme", "README", 20, 2, false);
        trafficJdbcRepository.insertPath(kafka.id(), today, latest, "/readme", "README", 38, 5, false);
        trafficJdbcRepository.insertPath(kafka.id(), today, latest, "/pulse", "Pulse", 17, 1, true);

        AnalyticsService.RepositoryTrafficSnapshot sevenDays = analyticsService.traffic(kafka.id(), "7d", false);
        assertThat(sevenDays.referrers()).containsExactly(
                new TrafficJdbcRepository.ReferrerRow("github.com", 81, 3),
                new TrafficJdbcRepository.ReferrerRow("mvnrepository.com", 1, 1)
        );
        assertThat(sevenDays.paths()).containsExactly(
                new TrafficJdbcRepository.PathRow("/readme", "README", 38, 5, false)
        );
        assertThat(analyticsService.traffic(kafka.id(), "7d", true).paths()).containsExactly(
                new TrafficJdbcRepository.PathRow("/readme", "README", 38, 5, false),
                new TrafficJdbcRepository.PathRow("/pulse", "Pulse", 17, 1, true)
        );
        assertThat(sevenDays.referrerSnapshotAt()).isEqualTo(latest);
        assertThat(sevenDays.pathSnapshotAt()).isEqualTo(latest);

        AnalyticsService.RepositoryTrafficSnapshot oneDay = analyticsService.traffic(kafka.id(), "1d", false);
        assertThat(oneDay.referrers()).isEqualTo(sevenDays.referrers());
        assertThat(oneDay.paths()).isEqualTo(sevenDays.paths());
    }

    @Test
    void trafficTotalsCompareWithThePreviousPeriod() {
        createRepos();
        LocalDate today = LocalDate.now(clock);
        trafficJdbcRepository.upsertDaily(kafka.id(), today.minusDays(20), 100, 50, 20, 10);
        trafficJdbcRepository.upsertDaily(kafka.id(), today.minusDays(8), 20, 10, 4, 2);
        trafficJdbcRepository.upsertDaily(kafka.id(), today.minusDays(1), 40, 5, 8, 1);
        trafficJdbcRepository.upsertDaily(kafka.id(), today, 7, 1, 1, 1);

        AnalyticsService.RepositoryTrafficSnapshot week = analyticsService.traffic(kafka.id(), "7d", false);
        assertThat(week.totals().views()).isEqualTo(new DashboardResponse.MetricValue(47, 135.0));
        assertThat(week.totals().uniqueVisitors()).isEqualTo(new DashboardResponse.MetricValue(6, -40.0));
        assertThat(week.totals().clones()).isEqualTo(new DashboardResponse.MetricValue(9, 125.0));
        assertThat(week.totals().uniqueCloners()).isEqualTo(new DashboardResponse.MetricValue(2, 0.0));

        AnalyticsService.RepositoryTrafficSnapshot oneDay = analyticsService.traffic(kafka.id(), "1d", false);
        assertThat(oneDay.totals().views()).isEqualTo(new DashboardResponse.MetricValue(40, null));

        AnalyticsService.RepositoryTrafficSnapshot all = analyticsService.traffic(kafka.id(), "all", false);
        assertThat(all.totals().views()).isEqualTo(new DashboardResponse.MetricValue(167, null));
    }

    @Test
    void trafficHistoryReturnsSnapshotValuesWithSignedDeltas() {
        createRepos();
        LocalDate today = LocalDate.now(clock);
        Instant before = today.minusDays(3).atTime(12, 0).atZone(clock.getZone()).toInstant();
        Instant current = today.minusDays(1).atTime(12, 0).atZone(clock.getZone()).toInstant();
        LocalDate beforeDate = today.minusDays(3);
        LocalDate currentDate = today.minusDays(1);
        trafficJdbcRepository.insertReferrers(kafka.id(), beforeDate, before, "github.com", 215, 4);
        trafficJdbcRepository.insertReferrers(kafka.id(), currentDate, current, "github.com", 230, 6);
        trafficJdbcRepository.insertReferrers(kafka.id(), currentDate, current, "doubao.com", 1, 1);
        trafficJdbcRepository.insertPath(kafka.id(), beforeDate, before, "/readme", "README", 38, 5, false);
        trafficJdbcRepository.insertPath(kafka.id(), currentDate, current, "/readme", "README", 50, 8, false);
        trafficJdbcRepository.insertPath(kafka.id(), currentDate, current, "/pulse", "Pulse", 17, 1, false);

        AnalyticsService.SnapshotHistoryResponse referrers =
                analyticsService.snapshotHistory(kafka.id(), "referrers", 2, false);
        assertThat(referrers.kind()).isEqualTo("REFERRERS");
        assertThat(referrers.from()).isEqualTo(today.minusDays(1));
        assertThat(referrers.dates()).containsExactly(today.minusDays(1));
        assertThat(referrers.rows()).containsExactly(
                new AnalyticsService.SnapshotHistoryRow("github.com", null, List.of(
                        new AnalyticsService.SnapshotHistoryCell(today.minusDays(1), 6, 230, 2, 15, false)
                )),
                new AnalyticsService.SnapshotHistoryRow("doubao.com", null, List.of(
                        new AnalyticsService.SnapshotHistoryCell(today.minusDays(1), 1, 1, null, null, true)
                ))
        );

        AnalyticsService.SnapshotHistoryResponse paths =
                analyticsService.snapshotHistory(kafka.id(), "paths", 14, false);
        assertThat(paths.dates()).containsExactly(today.minusDays(3), today.minusDays(1));
        assertThat(paths.rows()).containsExactly(
                new AnalyticsService.SnapshotHistoryRow("/readme", "README", List.of(
                        new AnalyticsService.SnapshotHistoryCell(today.minusDays(3), 5, 38, null, null, false),
                        new AnalyticsService.SnapshotHistoryCell(today.minusDays(1), 8, 50, 3, 12, false)
                )),
                new AnalyticsService.SnapshotHistoryRow("/pulse", "Pulse", List.of(
                        new AnalyticsService.SnapshotHistoryCell(today.minusDays(3), null, null, null, null, false),
                        new AnalyticsService.SnapshotHistoryCell(today.minusDays(1), 1, 17, null, null, true)
                ))
        );
    }

    @Test
    void trafficHistoryClampsTheRequestedWindowToTwoWeeks() {
        createRepos();
        LocalDate today = LocalDate.now(clock);

        AnalyticsService.SnapshotHistoryResponse wide =
                analyticsService.snapshotHistory(kafka.id(), null, 90, false);

        assertThat(wide.kind()).isEqualTo("REFERRERS");
        assertThat(wide.days()).isEqualTo(14);
        assertThat(wide.from()).isEqualTo(today.minusDays(13));
    }

    @Test
    void statsHistoryReturnsCollectedDaysInTheRequestedPeriod() {
        createRepos();
        LocalDate today = LocalDate.now(clock);
        trafficJdbcRepository.upsertDailyStats(kafka.id(), today.minusDays(10), 35, 8, 2, 0, 4);
        trafficJdbcRepository.upsertDailyStats(kafka.id(), today.minusDays(3), 40, 9, 3, 0, 5);
        trafficJdbcRepository.upsertDailyStats(kafka.id(), today, 41, 10, 3, 0, 6);

        AnalyticsService.RepositoryStatsHistory week = analyticsService.statsHistory(kafka.id(), "7d");
        assertThat(week.period()).isEqualTo("7d");
        assertThat(week.points()).containsExactly(
                new AnalyticsService.StatsHistoryPoint(today.minusDays(3), 40, 3, 9, 5),
                new AnalyticsService.StatsHistoryPoint(today, 41, 3, 10, 6)
        );
        assertThat(week.totals().stars()).isEqualTo(new AnalyticsService.StatsMetricTotal(41, 1, 2.5));
        assertThat(week.totals().forks()).isEqualTo(new AnalyticsService.StatsMetricTotal(3, 0, 0.0));
        assertThat(week.totals().watchers()).isEqualTo(new AnalyticsService.StatsMetricTotal(10, 1, 11.1));
        assertThat(week.totals().contributors()).isEqualTo(new AnalyticsService.StatsMetricTotal(6, 1, 20.0));

        AnalyticsService.RepositoryStatsHistory all = analyticsService.statsHistory(kafka.id(), "all");
        assertThat(all.points()).extracting(AnalyticsService.StatsHistoryPoint::date)
                .containsExactly(today.minusDays(10), today.minusDays(3), today);
        assertThat(all.points().getFirst().contributors()).isEqualTo(4);
        assertThat(all.totals().stars()).isEqualTo(new AnalyticsService.StatsMetricTotal(41, null, null));
        assertThat(all.totals().forks().value()).isEqualTo(3);
        assertThat(all.totals().watchers().absoluteChange()).isNull();
        assertThat(all.totals().contributors().growthPercent()).isNull();
    }

    @Test
    void statsHistoryKeepsTheLatestLevelWhenTheWindowHasNoChange() {
        createRepos();
        LocalDate today = LocalDate.now(clock);
        AnalyticsService.RepositoryStatsHistory empty = analyticsService.statsHistory(kafka.id(), "30d");
        assertThat(empty.points()).isEmpty();
        assertThat(empty.totals().stars().value()).isNull();
        assertThat(empty.totals().stars().absoluteChange()).isNull();

        trafficJdbcRepository.upsertDailyStats(kafka.id(), today.minusDays(10), 35, 8, 2, 0, 4);

        AnalyticsService.RepositoryStatsHistory day = analyticsService.statsHistory(kafka.id(), "1d");
        assertThat(day.points()).isEmpty();
        assertThat(day.totals().stars()).isEqualTo(new AnalyticsService.StatsMetricTotal(35, null, null));
        assertThat(day.totals().forks().value()).isEqualTo(2);
        assertThat(day.totals().watchers().value()).isEqualTo(8);
        assertThat(day.totals().contributors().value()).isEqualTo(4);

        AnalyticsService.RepositoryStatsHistory month = analyticsService.statsHistory(kafka.id(), "30d");
        assertThat(month.points()).containsExactly(
                new AnalyticsService.StatsHistoryPoint(today.minusDays(10), 35, 2, 8, 4)
        );
        assertThat(month.totals().stars()).isEqualTo(new AnalyticsService.StatsMetricTotal(35, null, null));
        assertThat(analyticsService.statsHistory(kafka.id(), "all").totals().stars().value()).isEqualTo(35);
    }

    @Test
    void statsHistoryUsesTheLevelChangeInsideTheWindow() {
        createRepos();
        LocalDate today = LocalDate.now(clock);
        trafficJdbcRepository.upsertDailyStats(kafka.id(), today.minusDays(1), 0, 0, 22, 0, 8);
        trafficJdbcRepository.upsertDailyStats(kafka.id(), today, 6, 0, 19, 0, 10);

        AnalyticsService.RepositoryStatsHistory month = analyticsService.statsHistory(kafka.id(), "30d");
        assertThat(month.totals().stars()).isEqualTo(new AnalyticsService.StatsMetricTotal(6, 6, null));
        assertThat(month.totals().forks()).isEqualTo(new AnalyticsService.StatsMetricTotal(19, -3, -13.6));
        assertThat(month.totals().watchers()).isEqualTo(new AnalyticsService.StatsMetricTotal(0, 0, 0.0));
        assertThat(month.totals().contributors()).isEqualTo(new AnalyticsService.StatsMetricTotal(10, 2, 25.0));

        AnalyticsService.RepositoryStatsHistory week = analyticsService.statsHistory(kafka.id(), "7d");
        assertThat(week.totals().stars().value()).isEqualTo(month.totals().stars().value());
        assertThat(week.totals().forks()).isEqualTo(month.totals().forks());
    }

    @Test
    void trafficHistoryRejectsAnUnknownKind() {
        createRepos();

        assertThatThrownBy(() -> analyticsService.snapshotHistory(kafka.id(), "sources", 7, false))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("sources");
        assertThat(analyticsService.snapshotHistory(kafka.id(), "paths", null, true).days()).isPositive();
        assertThat(analyticsService.portfolio("7d").repositories()).isEqualTo(2);
        assertThat(analyticsService.portfolio("all").stars()).isEqualTo(169);
        assertThat(analyticsService.portfolio("1d").repositories()).isEqualTo(2);
        assertThat(analyticsService.portfolio("90d").repositories()).isEqualTo(2);
        assertThat(analyticsService.portfolio("1y").repositories()).isEqualTo(2);
        assertThat(analyticsService.portfolio("nope").repositories()).isEqualTo(2);
        assertThat(analyticsService.traffic(kafka.id(), "all", true).repository().id()).isEqualTo(kafka.id());
    }

    @Test
    void portfolioHighlightsFollowTheSelectedPeriod() {
        createRepos();
        LocalDate today = LocalDate.now(clock);
        trafficJdbcRepository.upsertDaily(kafka.id(), today.minusDays(3), 20, 80, 1, 1);
        trafficJdbcRepository.upsertDaily(outbox.id(), today.minusDays(3), 8, 10, 1, 1);
        trafficJdbcRepository.upsertDaily(kafka.id(), today.minusDays(10), 10, 40, 1, 1);
        trafficJdbcRepository.upsertDaily(outbox.id(), today.minusDays(10), 8, 10, 1, 1);

        long outboxQuery = searchQueryJdbcRepository.insert(kafka.id(), "outbox", "outbox", true, 50).id();
        long kafkaQuery = searchQueryJdbcRepository.insert(outbox.id(), "kafka", "kafka", true, 50).id();
        succeed(outboxQuery, kafka.id(), today.minusDays(10), 22);
        succeed(outboxQuery, kafka.id(), today.minusDays(2), 14);
        succeed(kafkaQuery, outbox.id(), today.minusDays(10), 4);
        succeed(kafkaQuery, outbox.id(), today.minusDays(2), 11);

        growthEventJdbcRepository.insertManual(kafka.id(), today.minusDays(2).atStartOfDay(ZoneOffset.UTC).toInstant(), "PROMOTION", "LINKEDIN_POST", "Launch", null, null);
        growthEventJdbcRepository.insertManual(kafka.id(), today.minusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant(), "PROMOTION", "REDDIT_POST", "Thread", null, null);
        growthEventJdbcRepository.insertManual(outbox.id(), today.minusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant(), "PROMOTION", "CUSTOM", "Note", null, null);
        growthEventJdbcRepository.insertManual(kafka.id(), today.minusDays(40).atStartOfDay(ZoneOffset.UTC).toInstant(), "PROMOTION", "HABR_ARTICLE", "Old", null, null);

        DashboardResponse dashboard = analyticsService.dashboard("7d");
        assertThat(dashboard.highlights().rankRise().queryName()).isEqualTo("outbox");
        assertThat(dashboard.highlights().rankRise().fromRank()).isEqualTo(22);
        assertThat(dashboard.highlights().rankRise().toRank()).isEqualTo(14);
        assertThat(dashboard.highlights().rankRise().repositoryId()).isEqualTo(kafka.id());
        assertThat(dashboard.highlights().rankDrop().queryName()).isEqualTo("kafka");
        assertThat(dashboard.highlights().rankDrop().fromRank()).isEqualTo(4);
        assertThat(dashboard.highlights().rankDrop().toRank()).isEqualTo(11);
        assertThat(dashboard.highlights().events().count()).isEqualTo(3);
        assertThat(dashboard.highlights().events().repositoryId()).isEqualTo(kafka.id());
        assertThat(dashboard.highlights().events().repositoryNames()).containsExactly("acme/kafka-starter", "acme/spring-outbox");
        assertThat(dashboard.highlights().visitors().fullName()).isEqualTo("acme/kafka-starter");
    }

    private void succeed(long queryId, long repositoryId, LocalDate date, int position) {
        searchRunJdbcRepository.insertIgnore(queryId, repositoryId, date);
        searchRunJdbcRepository.markSuccess(searchRunJdbcRepository.find(queryId, date).orElseThrow().id(), 50, position);
    }

    @Test
    void portfolioStatsSumsTrackedSnapshotsInsideTheWindow() {
        createRepos();
        LocalDate today = LocalDate.now(clock);
        trafficJdbcRepository.upsertDaily(kafka.id(), today.minusDays(1), 1, 1, 0, 0);
        trafficJdbcRepository.upsertDaily(outbox.id(), today.minusDays(1), 1, 1, 0, 0);
        trafficJdbcRepository.upsertDailyStats(kafka.id(), today.minusDays(4), 100, 4, 2, 0, 0);
        trafficJdbcRepository.upsertDailyStats(outbox.id(), today.minusDays(4), 50, 1, 1, 0, 0);
        trafficJdbcRepository.upsertDailyStats(kafka.id(), today.minusDays(2), 106, 4, 2, 0, 4);
        trafficJdbcRepository.upsertDailyStats(kafka.id(), today.minusDays(1), 110, 4, 2, 0, 4);
        trafficJdbcRepository.upsertDailyStats(outbox.id(), today.minusDays(1), 50, 1, 1, 0, 0);

        DashboardResponse week = analyticsService.dashboard("7d");
        assertThat(week.stats().points()).extracting(DashboardResponse.StatsPoint::date).containsExactly(
                today.minusDays(4),
                today.minusDays(3),
                today.minusDays(2),
                today.minusDays(1)
        );
        assertThat(week.stats().points().get(1).stars()).isNull();
        assertThat(week.stats().points().get(2).stars()).isEqualTo(106);
        assertThat(week.stats().points().get(2).missingRepositories()).isEqualTo(1);
        assertThat(week.stats().totals().stars()).isEqualTo(new DashboardResponse.StatsTotal(160, 10, 6.7));
        assertThat(week.stats().totals().forks()).isEqualTo(new DashboardResponse.StatsTotal(3, 0, 0.0));
        assertThat(week.stats().totals().watchers()).isEqualTo(new DashboardResponse.StatsTotal(5, 0, 0.0));
        assertThat(week.stats().totals().contributors()).isEqualTo(new DashboardResponse.StatsTotal(4, 4, null));

        DashboardResponse day = analyticsService.dashboard("1d");
        assertThat(day.stats().points()).extracting(DashboardResponse.StatsPoint::date).containsExactly(today.minusDays(1));
        assertThat(day.stats().totals().stars().value()).isEqualTo(160);
        assertThat(day.stats().totals().stars().absoluteChange()).isNull();

        DashboardResponse all = analyticsService.dashboard("all");
        assertThat(all.stats().totals().stars().value()).isEqualTo(160);
        assertThat(all.stats().totals().stars().absoluteChange()).isNull();
        assertThat(all.stats().totals().stars().growthPercent()).isNull();
    }

    private void createRepos() {
        GitHubOwner owner = ownerJdbcRepository.upsert(100L, "acme", OwnerType.USER, null, "https://github.com/acme");
        kafka = track(owner, 201L, "kafka-starter", "acme/kafka-starter", 41);
        outbox = track(owner, 202L, "spring-outbox", "acme/spring-outbox", 128);
    }

    private Repository track(GitHubOwner owner, long githubId, String name, String fullName, int stars) {
        Repository repository = repositoryJdbcRepository.upsertKeepingTracking(new Repository(
                null, githubId, owner.id(), name, fullName, name, "PUBLIC", "main", "Java",
                false, false, stars, 0, 2, 0, 0, false,
                Instant.parse("2024-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"),
                null, null, null, null, null, null
        ));
        repositoryJdbcRepository.markAccountAccessible(repository.id());
        repositoryJdbcRepository.setTracking(repository.id(), true);
        return repositoryJdbcRepository.findById(repository.id()).orElseThrow();
    }
}
