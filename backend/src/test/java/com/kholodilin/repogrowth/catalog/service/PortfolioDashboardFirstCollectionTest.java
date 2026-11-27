package com.kholodilin.repogrowth.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.kholodilin.repogrowth.catalog.service.DashboardResponse.DashboardState;
import com.kholodilin.repogrowth.catalog.model.ActivityStatus;
import com.kholodilin.repogrowth.catalog.model.Repository;
import com.kholodilin.repogrowth.catalog.service.ActivityClassifier;
import com.kholodilin.repogrowth.catalog.service.RepositoryHealthService;
import com.kholodilin.repogrowth.catalog.service.RepositoryService;
import com.kholodilin.repogrowth.collection.model.CollectionJob;
import com.kholodilin.repogrowth.collection.model.CollectionJobStatus;
import com.kholodilin.repogrowth.collection.model.CollectionJobType;
import com.kholodilin.repogrowth.collection.model.CollectionRun;
import com.kholodilin.repogrowth.collection.model.CollectionRunStatus;
import com.kholodilin.repogrowth.collection.planner.PlanningWindow;
import com.kholodilin.repogrowth.collection.service.CollectionTrafficProbe;
import com.kholodilin.repogrowth.stats.service.StatsReadService;
import com.kholodilin.repogrowth.traffic.service.TrafficReadService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PortfolioDashboardFirstCollectionTest {

  @Test
  void copiesJobErrorsBeforeTrafficExists() {
    Instant now = Instant.parse("2026-09-23T06:00:00Z");
    Repository repository =
        new Repository(
            13L,
            99L,
            1L,
            "outbox",
            "acme/outbox",
            "desc",
            "PUBLIC",
            "main",
            "Java",
            false,
            false,
            21,
            0,
            16,
            1,
            7,
            true,
            now,
            now,
            now,
            now,
            null,
            null,
            now,
            now);
    CollectionRun run =
        new CollectionRun(
            5L,
            13L,
            LocalDate.parse("2026-09-23"),
            CollectionRunStatus.RUNNING,
            1,
            0,
            0,
            now,
            null);
    CollectionJob job =
        new CollectionJob(
            1L,
            5L,
            13L,
            CollectionJobType.REFERRERS,
            LocalDate.parse("2026-09-23"),
            CollectionJobStatus.FAILED,
            1,
            null,
            null,
            null,
            null,
            null,
            "GITHUB_API_ERROR",
            "rate limit");
    RepositoryService repositoryService = mock(RepositoryService.class);
    TrafficReadService traffic = mock(TrafficReadService.class);
    CollectionTrafficProbe collections = mock(CollectionTrafficProbe.class);
    
    ActivityClassifier activity = mock(ActivityClassifier.class);
    RepositoryHealthService health = mock(RepositoryHealthService.class);
    Repository unrated =
        new Repository(
            14L,
            100L,
            1L,
            "plain",
            "acme/plain",
            "desc",
            "PUBLIC",
            "main",
            "Java",
            false,
            false,
            1,
            0,
            0,
            0,
            0,
            true,
            now,
            now,
            now,
            now,
            null,
            null,
            now,
            now);
    when(repositoryService.tracked()).thenReturn(List.of(repository, unrated));
    when(repositoryService.signalsFor(anyList())).thenReturn(Map.of());
    when(traffic.earliestTrackedTrafficDate()).thenReturn(Optional.empty());
    when(traffic.hasTrackedTraffic()).thenReturn(false);
    when(collections.latestPerTrackedRepository()).thenReturn(List.of(run));
    when(collections.latestCompletedAtForTracked()).thenReturn(null);
    when(collections.findByRunIds(any())).thenReturn(List.of(job));
    when(health.discoverabilityScore(repository))
        .thenReturn(new RepositoryHealthService.DiscoverabilityScore(4, 13));
    when(activity.classify(anyBoolean(), nullable(Instant.class)))
        .thenReturn(ActivityStatus.ACTIVE);
PortfolioDashboardService service =
        new PortfolioDashboardService(
        repositoryService,
        traffic,
        mock(StatsReadService.class),
        collections,
            activity,
            health,
            mock(PortfolioHighlightsService.class),
            mock(PlanningWindow.class),
            mock(com.kholodilin.repogrowth.administration.service.PublicationService.class),
            Clock.fixed(now, ZoneOffset.UTC));

    var response = service.dashboard("30d", true);

    assertThat(response.state()).isEqualTo(DashboardState.FIRST_COLLECTION);
    assertThat(response.repositories())
        .filteredOn(row -> row.id() == 13L)
        .singleElement()
        .satisfies(row -> {
          assertThat(row.jobs())
              .singleElement()
              .satisfies(status -> {
                assertThat(status.jobType()).isEqualTo("REFERRERS");
                assertThat(status.status()).isEqualTo("FAILED");
                assertThat(status.errorMessage()).isEqualTo("rate limit");
              });
          assertThat(row.discoverabilityPassed()).isEqualTo(4);
          assertThat(row.discoverabilityTotal()).isEqualTo(13);
        });
    assertThat(response.repositories())
        .filteredOn(row -> row.id() == 14L)
        .singleElement()
        .satisfies(row -> {
          assertThat(row.discoverabilityPassed()).isNull();
          assertThat(row.discoverabilityTotal()).isNull();
        });
  }
}
