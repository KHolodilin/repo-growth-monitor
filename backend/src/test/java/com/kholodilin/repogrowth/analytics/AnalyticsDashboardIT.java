package com.kholodilin.repogrowth.analytics;

import com.kholodilin.repogrowth.analytics.api.DashboardResponse;
import com.kholodilin.repogrowth.analytics.api.DashboardResponse.DashboardState;
import com.kholodilin.repogrowth.analytics.application.AnalyticsService;
import com.kholodilin.repogrowth.collection.domain.CollectionJobType;
import com.kholodilin.repogrowth.collection.domain.CollectionRun;
import com.kholodilin.repogrowth.collection.persistence.CollectionJobJdbcRepository;
import com.kholodilin.repogrowth.collection.persistence.CollectionRunJdbcRepository;
import com.kholodilin.repogrowth.repository.domain.GitHubOwner;
import com.kholodilin.repogrowth.repository.domain.OwnerType;
import com.kholodilin.repogrowth.repository.domain.Repository;
import com.kholodilin.repogrowth.repository.persistence.GitHubOwnerJdbcRepository;
import com.kholodilin.repogrowth.repository.persistence.RepositoryJdbcRepository;
import com.kholodilin.repogrowth.support.AbstractPostgresTest;
import com.kholodilin.repogrowth.traffic.persistence.TrafficJdbcRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

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

    private Repository kafka;
    private Repository outbox;

    @BeforeEach
    void seed() {
        jdbcClient.sql("DELETE FROM collection_job").update();
        jdbcClient.sql("DELETE FROM collection_run").update();
        jdbcClient.sql("DELETE FROM traffic_path_snapshot").update();
        jdbcClient.sql("DELETE FROM traffic_referrer_snapshot").update();
        jdbcClient.sql("DELETE FROM traffic_daily").update();
        jdbcClient.sql("DELETE FROM repository_daily_stats").update();
        jdbcClient.sql("DELETE FROM search_result").update();
        jdbcClient.sql("DELETE FROM search_run").update();
        jdbcClient.sql("DELETE FROM search_query").update();
        jdbcClient.sql("DELETE FROM growth_event").update();
        jdbcClient.sql("DELETE FROM growth_event_setting").update();
        jdbcClient.sql("DELETE FROM growth_event_state").update();
        jdbcClient.sql("DELETE FROM repository_health").update();
        jdbcClient.sql("DELETE FROM repository_topics").update();
        jdbcClient.sql("DELETE FROM repository").update();
        jdbcClient.sql("DELETE FROM github_owner").update();
    }

    @Test
    void noTrackedRepositoriesReturnsEmptyState() {
        DashboardResponse dashboard = analyticsService.dashboard("30d");
        assertThat(dashboard.state()).isEqualTo(DashboardState.NO_REPOSITORIES);
        assertThat(dashboard.summary().repositories()).isZero();
        assertThat(dashboard.traffic()).isEmpty();
        assertThat(dashboard.repositories()).isEmpty();
    }

    @Test
    void trackedRepositoriesWithoutTrafficReturnFirstCollectionState() {
        createRepos();
        DashboardResponse dashboard = analyticsService.dashboard("30d");
        assertThat(dashboard.state()).isEqualTo(DashboardState.FIRST_COLLECTION);
        assertThat(dashboard.summary().repositories()).isEqualTo(2);
        assertThat(dashboard.summary().views().value()).isZero();
        assertThat(dashboard.traffic()).isEmpty();
        assertThat(dashboard.repositories()).hasSize(2);
        assertThat(dashboard.repositories()).extracting(DashboardResponse.RepositoryRow::fullName)
                .containsExactlyInAnyOrder("acme/kafka-starter", "acme/spring-outbox");
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

        trafficJdbcRepository.upsertDailyStats(kafka.id(), today.minusDays(10), 35, 8, 2, 0);
        trafficJdbcRepository.upsertDailyStats(outbox.id(), today.minusDays(10), 120, 20, 4, 1);

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
        assertThat(dashboard.partialData()).isNotNull();
        assertThat(dashboard.partialData().present()).isTrue();
        assertThat(dashboard.partialData().message()).contains("because the service was not running.");
        assertThat(dashboard.collectionWarning()).isNotNull();
        assertThat(dashboard.collectionWarning().partialRepositories()).isEqualTo(1);

        assertThat(dashboard.traffic()).extracting(DashboardResponse.TrafficPoint::date)
                .containsExactly(today.minusDays(6), today.minusDays(4));
        assertThat(dashboard.traffic()).noneMatch(point -> point.date().equals(today.minusDays(5)));
        assertThat(dashboard.traffic()).noneMatch(point -> point.date().equals(today));

        DashboardResponse.RepositoryRow kafkaRow = dashboard.repositories().stream()
                .filter(row -> row.fullName().equals("acme/kafka-starter"))
                .findFirst()
                .orElseThrow();
        assertThat(kafkaRow.visitors()).isEqualTo(100);
        assertThat(kafkaRow.growthPercent()).isEqualTo(100.0);
        assertThat(kafkaRow.collectionStatus()).isEqualTo("PARTIAL");
        assertThat(kafkaRow.jobs()).extracting(DashboardResponse.JobStatus::jobType)
                .contains("TRAFFIC", "POPULAR_PATHS");

        DashboardResponse all = analyticsService.dashboard("all");
        assertThat(all.summary().views().growthPercent()).isNull();
        assertThat(all.summary().stars().change()).isNull();
    }

    @Test
    void trafficAggregatesReferrersAndPathsForSelectedPeriod() {
        createRepos();
        LocalDate today = LocalDate.now(clock);
        Instant snapshotAt = today.atTime(12, 0).atZone(clock.getZone()).toInstant();

        trafficJdbcRepository.upsertDaily(kafka.id(), today.minusDays(15), 20, 1, 0, 0);
        trafficJdbcRepository.upsertDaily(kafka.id(), today.minusDays(14), 3, 1, 0, 0);
        trafficJdbcRepository.upsertDaily(kafka.id(), today.minusDays(9), 2, 2, 0, 0);
        trafficJdbcRepository.upsertDaily(kafka.id(), today.minusDays(8), 1, 1, 0, 0);
        trafficJdbcRepository.upsertDaily(kafka.id(), today.minusDays(7), 1, 1, 0, 0);
        trafficJdbcRepository.upsertDaily(kafka.id(), today.minusDays(5), 1, 1, 0, 0);
        trafficJdbcRepository.upsertDaily(kafka.id(), today.minusDays(4), 37, 2, 0, 0);
        trafficJdbcRepository.upsertDaily(kafka.id(), today.minusDays(3), 22, 1, 0, 0);
        trafficJdbcRepository.upsertDaily(kafka.id(), today.minusDays(2), 6, 1, 0, 0);
        trafficJdbcRepository.upsertDaily(kafka.id(), today.minusDays(1), 6, 1, 0, 0);

        trafficJdbcRepository.insertReferrers(kafka.id(), snapshotAt, "github.com", 81, 3);
        trafficJdbcRepository.insertReferrers(kafka.id(), snapshotAt, "mvnrepository.com", 1, 1);
        trafficJdbcRepository.insertPath(kafka.id(), snapshotAt, "/readme", "README", 38, 5);
        trafficJdbcRepository.insertPath(kafka.id(), snapshotAt, "/pulse", "Pulse", 17, 1);

        AnalyticsService.RepositoryTrafficSnapshot sevenDays = analyticsService.traffic(kafka.id(), "7d");
        assertThat(sevenDays.totals().views()).isEqualTo(72);
        assertThat(sevenDays.referrers()).containsExactly(
                new TrafficJdbcRepository.ReferrerRow("github.com", 77, 2),
                new TrafficJdbcRepository.ReferrerRow("mvnrepository.com", 1, 1)
        );
        assertThat(sevenDays.paths()).containsExactly(
                new TrafficJdbcRepository.PathRow("/readme", "README", 36, 3),
                new TrafficJdbcRepository.PathRow("/pulse", "Pulse", 16, 1)
        );

        AnalyticsService.RepositoryTrafficSnapshot thirtyDays = analyticsService.traffic(kafka.id(), "30d");
        assertThat(thirtyDays.referrers()).containsExactly(
                new TrafficJdbcRepository.ReferrerRow("github.com", 81, 3),
                new TrafficJdbcRepository.ReferrerRow("mvnrepository.com", 1, 1)
        );
        assertThat(thirtyDays.paths()).containsExactly(
                new TrafficJdbcRepository.PathRow("/readme", "README", 38, 5),
                new TrafficJdbcRepository.PathRow("/pulse", "Pulse", 17, 1)
        );
    }

    @Test
    void referrerHistoryUsesSnapshotDeltasAndLooksBackBeforeThePeriod() {
        createRepos();
        LocalDate today = LocalDate.now(clock);
        Instant before = today.minusDays(3).atTime(12, 0).atZone(clock.getZone()).toInstant();
        Instant current = today.minusDays(1).atTime(12, 0).atZone(clock.getZone()).toInstant();
        trafficJdbcRepository.insertReferrers(kafka.id(), before, "github.com", 215, 4);
        trafficJdbcRepository.insertReferrers(kafka.id(), current, "github.com", 230, 6);
        trafficJdbcRepository.insertReferrers(kafka.id(), current, "doubao.com", 1, 1);
        trafficJdbcRepository.insertPath(kafka.id(), before, "/readme", "README", 38, 5);
        trafficJdbcRepository.insertPath(kafka.id(), current, "/readme", "README", 50, 8);
        trafficJdbcRepository.insertPath(kafka.id(), current, "/pulse", "Pulse", 17, 1);

        AnalyticsService.ReferrerHistoryResponse history = analyticsService.referrerHistory(kafka.id(), "1d");
        assertThat(history.snapshotCount()).isEqualTo(2);
        assertThat(history.sources()).filteredOn(source -> source.source().equals("github.com"))
                .singleElement()
                .satisfies(source -> {
                    assertThat(source.views()).isEqualTo(15);
                    assertThat(source.uniqueVisitors()).isEqualTo(2);
                    assertThat(source.points()).hasSize(1);
                    assertThat(source.points().getFirst().date()).isEqualTo(today.minusDays(1));
                    assertThat(source.points().getFirst().views()).isEqualTo(15);
                    assertThat(source.points().getFirst().visitors()).isEqualTo(2);
                    assertThat(source.points().getFirst().previousSnapshotDate()).isEqualTo(today.minusDays(3));
                });
        assertThat(history.sources()).noneMatch(source -> source.source().equals("doubao.com"));
        assertThat(history.pathSnapshotCount()).isEqualTo(2);
        assertThat(history.paths()).containsExactly(
                new AnalyticsService.ReferrerHistoryPath("/readme", "README", 12, 3)
        );
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
