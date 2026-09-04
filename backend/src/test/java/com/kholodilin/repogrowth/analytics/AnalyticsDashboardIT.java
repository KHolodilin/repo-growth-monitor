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
    void trafficCardsShowTheLatestSnapshotForEveryPeriod() {
        createRepos();
        LocalDate today = LocalDate.now(clock);
        Instant before = today.minusDays(3).atTime(12, 0).atZone(clock.getZone()).toInstant();
        Instant latest = today.atTime(12, 0).atZone(clock.getZone()).toInstant();

        trafficJdbcRepository.upsertDaily(kafka.id(), today.minusDays(4), 37, 2, 0, 0);
        trafficJdbcRepository.upsertDaily(kafka.id(), today.minusDays(1), 6, 1, 0, 0);

        trafficJdbcRepository.insertReferrers(kafka.id(), before, "github.com", 40, 2);
        trafficJdbcRepository.insertReferrers(kafka.id(), latest, "github.com", 81, 3);
        trafficJdbcRepository.insertReferrers(kafka.id(), latest, "mvnrepository.com", 1, 1);
        trafficJdbcRepository.insertPath(kafka.id(), before, "/readme", "README", 20, 2);
        trafficJdbcRepository.insertPath(kafka.id(), latest, "/readme", "README", 38, 5);
        trafficJdbcRepository.insertPath(kafka.id(), latest, "/pulse", "Pulse", 17, 1);

        AnalyticsService.RepositoryTrafficSnapshot sevenDays = analyticsService.traffic(kafka.id(), "7d");
        assertThat(sevenDays.referrers()).containsExactly(
                new TrafficJdbcRepository.ReferrerRow("github.com", 81, 3),
                new TrafficJdbcRepository.ReferrerRow("mvnrepository.com", 1, 1)
        );
        assertThat(sevenDays.paths()).containsExactly(
                new TrafficJdbcRepository.PathRow("/readme", "README", 38, 5),
                new TrafficJdbcRepository.PathRow("/pulse", "Pulse", 17, 1)
        );
        assertThat(sevenDays.referrerSnapshotAt()).isEqualTo(latest);
        assertThat(sevenDays.pathSnapshotAt()).isEqualTo(latest);

        AnalyticsService.RepositoryTrafficSnapshot oneDay = analyticsService.traffic(kafka.id(), "1d");
        assertThat(oneDay.referrers()).isEqualTo(sevenDays.referrers());
        assertThat(oneDay.paths()).isEqualTo(sevenDays.paths());
    }

    @Test
    void trafficHistoryReturnsSnapshotValuesWithSignedDeltas() {
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

        AnalyticsService.SnapshotHistoryResponse referrers =
                analyticsService.snapshotHistory(kafka.id(), "referrers", 2);
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
                analyticsService.snapshotHistory(kafka.id(), "paths", 14);
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
                analyticsService.snapshotHistory(kafka.id(), null, 90);

        assertThat(wide.kind()).isEqualTo("REFERRERS");
        assertThat(wide.days()).isEqualTo(14);
        assertThat(wide.from()).isEqualTo(today.minusDays(13));
    }

    @Test
    void trafficHistoryRejectsAnUnknownKind() {
        createRepos();

        assertThatThrownBy(() -> analyticsService.snapshotHistory(kafka.id(), "sources", 7))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("sources");
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
