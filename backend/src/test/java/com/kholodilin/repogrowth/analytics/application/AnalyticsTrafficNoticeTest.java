package com.kholodilin.repogrowth.analytics.application;

import com.kholodilin.repogrowth.analytics.api.DashboardResponse.Highlights;
import com.kholodilin.repogrowth.auth.security.CurrentUserAccess;
import com.kholodilin.repogrowth.collection.api.CollectionController;
import com.kholodilin.repogrowth.collection.domain.CollectionJob;
import com.kholodilin.repogrowth.collection.domain.CollectionJobStatus;
import com.kholodilin.repogrowth.collection.domain.CollectionJobType;
import com.kholodilin.repogrowth.collection.domain.CollectionRun;
import com.kholodilin.repogrowth.collection.domain.CollectionRunStatus;
import com.kholodilin.repogrowth.collection.persistence.CollectionJobJdbcRepository;
import com.kholodilin.repogrowth.collection.persistence.CollectionRunJdbcRepository;
import com.kholodilin.repogrowth.collection.planner.PlanningWindow;
import com.kholodilin.repogrowth.repository.application.RepositoryHealthService;
import com.kholodilin.repogrowth.repository.application.RepositoryService;
import com.kholodilin.repogrowth.repository.domain.GitHubOwner;
import com.kholodilin.repogrowth.repository.domain.OwnerType;
import com.kholodilin.repogrowth.repository.domain.Repository;
import com.kholodilin.repogrowth.search.application.ActivityClassifier;
import com.kholodilin.repogrowth.search.domain.ActivityStatus;
import com.kholodilin.repogrowth.traffic.persistence.TrafficJdbcRepository;
import com.kholodilin.repogrowth.traffic.persistence.TrafficJdbcRepository.LatestTrafficDate;
import com.kholodilin.repogrowth.traffic.persistence.TrafficJdbcRepository.PathSnapshot;
import com.kholodilin.repogrowth.traffic.persistence.TrafficJdbcRepository.ReferrerSnapshot;
import com.kholodilin.repogrowth.traffic.persistence.TrafficJdbcRepository.RepositoryPeriodTotals;
import com.kholodilin.repogrowth.traffic.persistence.TrafficJdbcRepository.StoredTrafficDay;
import com.kholodilin.repogrowth.traffic.persistence.TrafficJdbcRepository.TrafficTotals;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnalyticsTrafficNoticeTest {

    private static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 19);
    private static final LocalDate YESTERDAY = TODAY.minusDays(1);

    @Test
    void repositoryPageNamesAFailedJobAndAMissedRun() {
        Repository repository = repository(13L, "acme/outbox");
        RepositoryService repositories = mock(RepositoryService.class);
        TrafficJdbcRepository traffic = mock(TrafficJdbcRepository.class);
        CollectionRunJdbcRepository runs = mock(CollectionRunJdbcRepository.class);
        CollectionJobJdbcRepository jobs = mock(CollectionJobJdbcRepository.class);
        PlanningWindow window = mock(PlanningWindow.class);
        when(repositories.get(13L)).thenReturn(repository);
        when(repositories.owner(1L)).thenReturn(new GitHubOwner(
                1L, 9L, "acme", OwnerType.USER, null, "https://github.com/acme", NOW, NOW
        ));
        when(traffic.history(anyLong(), any())).thenReturn(List.of());
        when(traffic.totalsInRange(anyLong(), any(), any())).thenReturn(new TrafficTotals(0, 0, 0, 0));
        when(runs.latestForRepository(13L)).thenReturn(Optional.empty());
        when(traffic.latestReferrerSnapshot(13L)).thenReturn(new ReferrerSnapshot(null, List.of()));
        when(traffic.latestPathSnapshot(anyLong(), anyBoolean())).thenReturn(new PathSnapshot(null, List.of()));
        CollectionRun run = new CollectionRun(
                5L, 13L, TODAY, CollectionRunStatus.PARTIAL, 2, 1, 1, NOW, NOW
        );
        when(runs.find(13L, TODAY)).thenReturn(Optional.of(run));
        when(jobs.findByRun(5L)).thenReturn(List.of(
                job(2L, 13L, CollectionJobType.REFERRERS, CollectionJobStatus.SUCCESS, null, null),
                job(1L, 13L, CollectionJobType.TRAFFIC, CollectionJobStatus.FAILED, "GITHUB_API_ERROR", "boom")
        ));
        when(traffic.latestDate(13L)).thenReturn(Optional.of(TODAY.minusDays(3)));
        when(window.isOpen()).thenReturn(true);
        AnalyticsService service = service(repositories, traffic, runs, jobs, window);

        assertThat(service.traffic(13L, "7d", false).trafficGap().reason()).isEqualTo("COLLECTION_FAILED");

        when(runs.find(13L, TODAY)).thenReturn(Optional.empty());
        when(traffic.latestDate(13L)).thenReturn(Optional.empty());
        when(window.isOpen()).thenReturn(false);
        assertThat(service.traffic(13L, "7d", false).trafficGap().reason()).isEqualTo("COLLECTION_NOT_EXECUTED");

        when(traffic.latestDate(13L)).thenReturn(Optional.of(YESTERDAY));
        when(window.isOpen()).thenReturn(true);
        assertThat(service.traffic(13L, "7d", false).trafficGap()).isNull();
    }

    @Test
    void portfolioSkipsACompleteRepositoryAndANullLatestDate() {
        Repository complete = repository(13L, "acme/complete");
        Repository missing = repository(14L, "acme/missing");
        RepositoryService repositories = mock(RepositoryService.class);
        TrafficJdbcRepository traffic = mock(TrafficJdbcRepository.class);
        CollectionRunJdbcRepository runs = mock(CollectionRunJdbcRepository.class);
        CollectionJobJdbcRepository jobs = mock(CollectionJobJdbcRepository.class);
        PlanningWindow window = mock(PlanningWindow.class);
        RepositoryHealthService health = mock(RepositoryHealthService.class);
        when(repositories.tracked()).thenReturn(List.of(complete, missing));
        when(repositories.signalsFor(any())).thenReturn(Map.of());
        when(traffic.earliestTrackedTrafficDate()).thenReturn(Optional.of(TODAY.minusDays(6)));
        when(traffic.hasTrackedTraffic()).thenReturn(true);
        when(traffic.portfolioTotalsInRange(any(), any())).thenReturn(new TrafficTotals(1, 1, 1, 1));
        when(traffic.starsChangeSince(any())).thenReturn(0L);
        when(traffic.dailyByRepository(any(), any())).thenReturn(List.of());
        when(traffic.trackedDailyStats(any(), any())).thenReturn(List.of());
        when(traffic.latestTrackedDailyStats()).thenReturn(List.of());
        when(traffic.trackedTrafficDays(any(), any())).thenReturn(List.of(
                new StoredTrafficDay(13L, TODAY.minusDays(6)),
                new StoredTrafficDay(13L, TODAY.minusDays(5)),
                new StoredTrafficDay(13L, TODAY.minusDays(4)),
                new StoredTrafficDay(13L, TODAY.minusDays(3)),
                new StoredTrafficDay(13L, TODAY.minusDays(2)),
                new StoredTrafficDay(13L, YESTERDAY),
                new StoredTrafficDay(14L, TODAY.minusDays(2))
        ));
        when(traffic.latestTrackedTrafficDates()).thenReturn(List.of(
                new LatestTrafficDate(13L, YESTERDAY),
                new LatestTrafficDate(14L, TODAY.minusDays(2)),
                new LatestTrafficDate(15L, null)
        ));
        CollectionRun run = new CollectionRun(
                5L, 13L, TODAY, CollectionRunStatus.SUCCESS, 2, 2, 0, NOW, NOW
        );
        when(runs.trackedOn(TODAY)).thenReturn(List.of(run));
        when(runs.latestPerTrackedRepository()).thenReturn(List.of());
        when(runs.latestCompletedAtForTracked()).thenReturn(null);
        when(jobs.findByRunIds(any())).thenReturn(List.of(
                job(2L, 13L, CollectionJobType.REFERRERS, CollectionJobStatus.SUCCESS, null, null),
                job(1L, 13L, CollectionJobType.TRAFFIC, CollectionJobStatus.SUCCESS, null, null),
                job(3L, 14L, CollectionJobType.TRAFFIC, CollectionJobStatus.SUCCESS, null, null)
        ));
        when(health.discoverabilityScore(any())).thenReturn(null);
        when(window.isOpen()).thenReturn(true);
        when(traffic.totalsByTrackedRepository(any(), any())).thenReturn(List.of(
                totals(13L, "acme/complete"),
                totals(14L, "acme/missing")
        ));
        PortfolioHighlightsService highlights = mock(PortfolioHighlightsService.class);
        when(highlights.build(any(), any())).thenReturn(Highlights.none());
        ActivityClassifier activity = mock(ActivityClassifier.class);
        when(activity.classify(anyBoolean(), nullable(Instant.class))).thenReturn(ActivityStatus.ACTIVE);
        CurrentUserAccess access = mock(CurrentUserAccess.class);
        when(access.canSeePrivateRepositories()).thenReturn(true);
        AnalyticsService service = new AnalyticsService(
                repositories,
                traffic,
                runs,
                jobs,
                mock(CollectionController.class),
                activity,
                health,
                highlights,
                window,
                Clock.fixed(NOW, ZoneOffset.UTC),
                access
        );

        var dashboard = service.dashboard("7d");

        assertThat(dashboard.repositories()).filteredOn(row -> row.id() == 13L).singleElement()
                .satisfies(row -> assertThat(row.trafficNotice()).isNull());
        assertThat(dashboard.repositories()).filteredOn(row -> row.id() == 14L).singleElement()
                .satisfies(row -> {
                    assertThat(row.trafficNotice().reason()).isEqualTo("GITHUB_DATA_MISSING");
                    assertThat(row.trafficNotice().date()).isEqualTo(YESTERDAY);
                });
    }

    private static AnalyticsService service(
            RepositoryService repositories,
            TrafficJdbcRepository traffic,
            CollectionRunJdbcRepository runs,
            CollectionJobJdbcRepository jobs,
            PlanningWindow window
    ) {
        CurrentUserAccess access = mock(CurrentUserAccess.class);
        when(access.canSeePrivateRepositories()).thenReturn(true);
        return new AnalyticsService(
                repositories,
                traffic,
                runs,
                jobs,
                mock(CollectionController.class),
                mock(ActivityClassifier.class),
                mock(RepositoryHealthService.class),
                mock(PortfolioHighlightsService.class),
                window,
                Clock.fixed(NOW, ZoneOffset.UTC),
                access
        );
    }

    private static Repository repository(long id, String fullName) {
        return new Repository(
                id, 99L, 1L, fullName.substring(fullName.indexOf('/') + 1), fullName, "desc", "PUBLIC", "main", "Java",
                false, false, 21, 0, 16, 1, 7, true,
                NOW, NOW, NOW, NOW, null, null, NOW, NOW
        );
    }

    private static CollectionJob job(
            long id,
            long repositoryId,
            CollectionJobType type,
            CollectionJobStatus status,
            String errorCode,
            String errorMessage
    ) {
        return new CollectionJob(
                id, 5L, repositoryId, type, TODAY,
                status, 1, null, null, null, null, NOW, errorCode, errorMessage
        );
    }

    private static RepositoryPeriodTotals totals(long id, String fullName) {
        return new RepositoryPeriodTotals(id, fullName, 1, 2, 3, 4, 5, false, NOW, NOW);
    }
}
