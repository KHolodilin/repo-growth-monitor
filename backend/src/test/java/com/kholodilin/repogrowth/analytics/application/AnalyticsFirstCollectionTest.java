package com.kholodilin.repogrowth.analytics.application;

import com.kholodilin.repogrowth.analytics.api.DashboardResponse.DashboardState;
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
import com.kholodilin.repogrowth.repository.domain.Repository;
import com.kholodilin.repogrowth.search.application.ActivityClassifier;
import com.kholodilin.repogrowth.search.domain.ActivityStatus;
import com.kholodilin.repogrowth.traffic.persistence.TrafficJdbcRepository;
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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnalyticsFirstCollectionTest {

    @Test
    void copiesJobErrorsBeforeTrafficExists() {
        Instant now = Instant.parse("2026-09-23T06:00:00Z");
        Repository repository = new Repository(
                13L, 99L, 1L, "outbox", "acme/outbox", "desc", "PUBLIC", "main", "Java",
                false, false, 21, 0, 16, 1, 7, true,
                now, now, now, now, null, null, now, now
        );
        CollectionRun run = new CollectionRun(
                5L, 13L, LocalDate.parse("2026-09-23"), CollectionRunStatus.RUNNING, 1, 0, 0, now, null
        );
        CollectionJob job = new CollectionJob(
                1L, 5L, 13L, CollectionJobType.REFERRERS, LocalDate.parse("2026-09-23"),
                CollectionJobStatus.FAILED, 1, null, null, null, null, null, "GITHUB_API_ERROR", "rate limit"
        );
        RepositoryService repositoryService = mock(RepositoryService.class);
        TrafficJdbcRepository traffic = mock(TrafficJdbcRepository.class);
        CollectionRunJdbcRepository runs = mock(CollectionRunJdbcRepository.class);
        CollectionJobJdbcRepository jobs = mock(CollectionJobJdbcRepository.class);
        ActivityClassifier activity = mock(ActivityClassifier.class);
        RepositoryHealthService health = mock(RepositoryHealthService.class);
        Repository unrated = new Repository(
                14L, 100L, 1L, "plain", "acme/plain", "desc", "PUBLIC", "main", "Java",
                false, false, 1, 0, 0, 0, 0, true,
                now, now, now, now, null, null, now, now
        );
        when(repositoryService.tracked()).thenReturn(List.of(repository, unrated));
        when(repositoryService.signalsFor(anyList())).thenReturn(Map.of());
        when(traffic.earliestTrackedTrafficDate()).thenReturn(Optional.empty());
        when(traffic.hasTrackedTraffic()).thenReturn(false);
        when(runs.latestPerTrackedRepository()).thenReturn(List.of(run));
        when(runs.latestCompletedAtForTracked()).thenReturn(null);
        when(jobs.findByRunIds(any())).thenReturn(List.of(job));
        when(health.discoverabilityScore(repository)).thenReturn(new RepositoryHealthService.DiscoverabilityScore(4, 13));
        when(activity.classify(anyBoolean(), nullable(Instant.class))).thenReturn(ActivityStatus.ACTIVE);
        CurrentUserAccess access = mock(CurrentUserAccess.class);
        when(access.canSeePrivateRepositories()).thenReturn(true);
        AnalyticsService service = new AnalyticsService(
                repositoryService,
                traffic,
                runs,
                jobs,
                mock(CollectionController.class),
                activity,
                health,
                mock(PortfolioHighlightsService.class),
                mock(PlanningWindow.class),
                Clock.fixed(now, ZoneOffset.UTC),
                access
        );

        var response = service.dashboard("30d");

        assertThat(response.state()).isEqualTo(DashboardState.FIRST_COLLECTION);
        assertThat(response.repositories()).filteredOn(row -> row.id() == 13L).singleElement().satisfies(row -> {
            assertThat(row.jobs()).singleElement().satisfies(status -> {
                assertThat(status.jobType()).isEqualTo("REFERRERS");
                assertThat(status.status()).isEqualTo("FAILED");
                assertThat(status.errorMessage()).isEqualTo("rate limit");
            });
            assertThat(row.discoverabilityPassed()).isEqualTo(4);
            assertThat(row.discoverabilityTotal()).isEqualTo(13);
        });
        assertThat(response.repositories()).filteredOn(row -> row.id() == 14L).singleElement().satisfies(row -> {
            assertThat(row.discoverabilityPassed()).isNull();
            assertThat(row.discoverabilityTotal()).isNull();
        });
    }
}
