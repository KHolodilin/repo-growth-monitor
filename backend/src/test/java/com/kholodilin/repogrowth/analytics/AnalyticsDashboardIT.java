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

        DashboardResponse.TrafficPoint missing = dashboard.traffic().stream()
                .filter(point -> point.date().equals(today.minusDays(5)))
                .findFirst()
                .orElseThrow();
        assertThat(missing.views()).isNull();
        assertThat(missing.visitors()).isNull();
        assertThat(missing.clones()).isNull();
        assertThat(dashboard.traffic().getLast().date()).isEqualTo(today.minusDays(4));
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
        repositoryJdbcRepository.setTracking(repository.id(), true);
        return repositoryJdbcRepository.findById(repository.id()).orElseThrow();
    }
}
