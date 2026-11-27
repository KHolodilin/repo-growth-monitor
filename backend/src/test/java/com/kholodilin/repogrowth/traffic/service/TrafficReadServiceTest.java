package com.kholodilin.repogrowth.traffic.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.kholodilin.repogrowth.collection.model.CollectionJob;
import com.kholodilin.repogrowth.collection.model.CollectionJobStatus;
import com.kholodilin.repogrowth.collection.model.CollectionJobType;
import com.kholodilin.repogrowth.collection.model.CollectionRun;
import com.kholodilin.repogrowth.collection.model.CollectionRunStatus;
import com.kholodilin.repogrowth.collection.planner.PlanningWindow;
import com.kholodilin.repogrowth.collection.service.CollectionRunMapper;
import com.kholodilin.repogrowth.collection.service.CollectionTrafficProbe;
import com.kholodilin.repogrowth.common.api.ApiException;
import com.kholodilin.repogrowth.common.model.kpi.DataStatus;
import com.kholodilin.repogrowth.common.model.kpi.KpiScope;
import com.kholodilin.repogrowth.traffic.model.PathRow;
import com.kholodilin.repogrowth.traffic.model.PathSnapshot;
import com.kholodilin.repogrowth.traffic.model.ReferrerRow;
import com.kholodilin.repogrowth.traffic.model.ReferrerSnapshot;
import com.kholodilin.repogrowth.traffic.model.TrafficDaily;
import com.kholodilin.repogrowth.traffic.model.TrafficTotals;
import com.kholodilin.repogrowth.traffic.repository.TrafficJdbcRepository;
import com.kholodilin.repogrowth.traffic.service.SnapshotHistoryMath.Observation;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TrafficReadServiceTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);
  private static final Clock CLOCK =
      Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

  @Mock TrafficJdbcRepository traffic;
  @Mock CollectionTrafficProbe collections;
  @Mock CollectionRunMapper collectionRunMapper;
  @Mock PlanningWindow planningWindow;

  TrafficReadService service;

  @BeforeEach
  void setUp() {
    service =
        new TrafficReadService(traffic, collections, collectionRunMapper, planningWindow, CLOCK);
  }

  @Test
  void repositoryKpiReturnsComparableCompleteMetrics() {
    LocalDate end = TODAY.minusDays(1);
    LocalDate from = end.minusDays(6);
    when(traffic.observedDays(eq(Set.of(13L)), any(), any())).thenReturn(7L);
    when(traffic.totalsInRange(eq(13L), any(), any())).thenReturn(new TrafficTotals(70, 35, 14, 7));
    var kpi = service.repositoryKpi(13L, "7d");
    assertThat(kpi.scope()).isEqualTo(KpiScope.REPOSITORY);
    assertThat(kpi.metrics().views().dataStatus()).isIn(DataStatus.COMPLETE, DataStatus.PARTIAL);
    assertThat(kpi.metrics().views().value()).isNotNull();
    assertThat(from).isBeforeOrEqualTo(end);
  }

  @Test
  void ownerKpiAggregatesReadableRepositories() {
    when(traffic.observedDays(eq(Set.of(13L, 14L)), any(), any())).thenReturn(14L);
    when(traffic.totalsInRange(anyLong(), any(), any())).thenReturn(new TrafficTotals(10, 5, 2, 1));
    var kpi = service.ownerKpi(1L, List.of(13L, 14L), "7d");
    assertThat(kpi.scope()).isEqualTo(KpiScope.OWNER);
    assertThat(kpi.scopeId()).isEqualTo("1");
  }

  @Test
  void dailyHistoryFiltersPublishedEndAndOwnerMarksIncompleteDays() {
    when(traffic.history(13L, TODAY.minusDays(6)))
        .thenReturn(
            List.of(
                day(13L, TODAY.minusDays(2), 5, 2, 1, 1),
                day(13L, TODAY.minusDays(1), 6, 3, 1, 1),
                day(13L, TODAY, 9, 4, 1, 1)));
    when(collections.latestForRepository(13L)).thenReturn(Optional.empty());
    when(collections.find(eq(13L), eq(TODAY))).thenReturn(Optional.empty());
    when(planningWindow.isOpen()).thenReturn(true);
    when(traffic.latestDate(13L)).thenReturn(Optional.of(TODAY.minusDays(1)));
    var history = service.dailyHistory(13L, "7d");
    assertThat(history.points())
        .extracting(TrafficReadService.DailyPoint::date)
        .doesNotContain(TODAY);
    assertThat(history.points()).isNotEmpty();
    assertThat(history.lastCollection()).isNull();
    assertThat(history.trafficGap()).isNull();

    when(traffic.history(eq(13L), any()))
        .thenReturn(List.of(day(13L, TODAY.minusDays(1), 5, 2, 1, 1)));
    when(traffic.history(eq(14L), any())).thenReturn(List.of());
    var ownerHistory = service.ownerDailyHistory(1L, List.of(13L, 14L), "7d");
    assertThat(ownerHistory.points()).anySatisfy(point -> assertThat(point.views()).isNull());
    assertThat(ownerHistory.lastCollection()).isNull();
    assertThat(ownerHistory.trafficGap()).isNull();
  }

  @Test
  void dailyHistoryExposesLastCollectionAndTrafficGap() {
    when(traffic.history(eq(13L), any())).thenReturn(List.of());
    CollectionRun run =
        new CollectionRun(
            9L,
            13L,
            TODAY,
            CollectionRunStatus.SUCCESS,
            1,
            1,
            0,
            Instant.parse("2026-11-10T06:00:00Z"),
            Instant.parse("2026-11-10T06:01:00Z"));
    CollectionJob trafficJob =
        new CollectionJob(
            1L,
            9L,
            13L,
            CollectionJobType.TRAFFIC,
            TODAY,
            CollectionJobStatus.SUCCESS,
            1,
            null,
            null,
            null,
            Instant.parse("2026-11-10T06:00:00Z"),
            Instant.parse("2026-11-10T06:01:00Z"),
            null,
            null);
    CollectionRunMapper.CollectionRunView view =
        new CollectionRunMapper.CollectionRunView(
            9L,
            13L,
            TODAY.toString(),
            "SUCCESS",
            1,
            1,
            0,
            Instant.parse("2026-11-10T06:00:00Z"),
            Instant.parse("2026-11-10T06:01:00Z"),
            List.of());
    CollectionRunMapper.CollectionRunResponse response =
        new CollectionRunMapper.CollectionRunResponse(
            9L,
            13L,
            TODAY.toString(),
            "SUCCESS",
            1,
            1,
            0,
            Instant.parse("2026-11-10T06:00:00Z"),
            Instant.parse("2026-11-10T06:01:00Z"),
            List.of());
    when(collections.latestForRepository(13L)).thenReturn(Optional.of(run));
    when(collectionRunMapper.toView(run)).thenReturn(view);
    when(collectionRunMapper.toResponse(view)).thenReturn(response);
    when(collections.find(13L, TODAY)).thenReturn(Optional.of(run));
    when(collections.findByRun(9L)).thenReturn(List.of(trafficJob));
    when(planningWindow.isOpen()).thenReturn(true);
    when(traffic.latestDate(13L)).thenReturn(Optional.empty());

    var history = service.dailyHistory(13L, "7d");
    assertThat(history.lastCollection()).isEqualTo(response);
    assertThat(history.trafficGap().reason()).isEqualTo("GITHUB_DATA_MISSING");
  }

  @Test
  void latestSnapshotsAndHistoriesUseDedicatedKinds() {
    Instant at = Instant.parse("2026-11-09T12:00:00Z");
    when(traffic.latestReferrerSnapshot(13L))
        .thenReturn(new ReferrerSnapshot(at, List.of(new ReferrerRow("github.com", 8, 3))));
    when(traffic.latestPathSnapshot(13L, false))
        .thenReturn(
            new PathSnapshot(at, List.of(new PathRow("/acme/outbox", "Home", 6, 2, false))));
    assertThat(service.latestReferrers(13L).rows()).hasSize(1);
    assertThat(service.latestPaths(13L, false).rows().get(0).title()).isEqualTo("Home");
    assertThat(service.latestPaths(13L, false).rows().get(0).servicePath()).isFalse();

    when(traffic.referrerSnapshotsForDelta(eq(13L), any(), any()))
        .thenReturn(
            List.of(
                new Observation(TODAY.minusDays(2), "github.com", null, 4, 2),
                new Observation(TODAY.minusDays(1), "github.com", null, 8, 3)));
    when(traffic.pathSnapshotsForDelta(eq(13L), any(), any(), anyBoolean()))
        .thenReturn(List.of(new Observation(TODAY.minusDays(1), "/acme/outbox", "Home", 6, 2)));
    when(traffic.earliestPathSnapshotDate(13L, true)).thenReturn(Optional.of(TODAY.minusDays(20)));

    assertThat(service.referrerHistory(13L, "7d", null).kind()).isEqualTo("REFERRERS");
    assertThat(service.pathHistory(13L, "7d", null, false).kind()).isEqualTo("PATHS");
    assertThat(service.snapshotHistory(13L, "paths", "all", null, true).kind()).isEqualTo("PATHS");
    assertThat(service.snapshotHistory(13L, null, null, 3, false).days()).isEqualTo(3);
    assertThatThrownBy(() -> service.snapshotHistory(13L, "other", "7d", null, false))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("Unknown snapshot kind");
  }

  @Test
  void missingCoverageYieldsMissingKpiValues() {
    when(traffic.observedDays(eq(Set.of(13L)), any(), any())).thenReturn(0L);
    when(traffic.totalsInRange(eq(13L), any(), any())).thenReturn(new TrafficTotals(0, 0, 0, 0));
    var kpi = service.repositoryKpi(13L, "7d");
    assertThat(kpi.metrics().views().dataStatus()).isEqualTo(DataStatus.MISSING);
    assertThat(kpi.metrics().views().value()).isNull();
  }

  @Test
  void allTimeHistoryUsesEarliestStoredDay() {
    when(traffic.history(eq(13L), any()))
        .thenReturn(List.of(day(13L, TODAY.minusDays(5), 1, 1, 0, 0)));
    when(traffic.observedDays(eq(Set.of(13L)), any(), any())).thenReturn(1L);
    when(traffic.totalsInRange(eq(13L), any(), any())).thenReturn(new TrafficTotals(1, 1, 0, 0));
    when(traffic.referrerSnapshotsForDelta(eq(13L), any(), any())).thenReturn(List.of());
    when(collections.latestForRepository(13L)).thenReturn(Optional.empty());
    when(collections.find(eq(13L), eq(TODAY))).thenReturn(Optional.empty());
    when(planningWindow.isOpen()).thenReturn(true);
    when(traffic.latestDate(13L)).thenReturn(Optional.of(TODAY.minusDays(5)));
    var kpi = service.repositoryKpi(13L, "all");
    assertThat(kpi.comparison()).isNull();
    assertThat(service.dailyHistory(13L, "all").points()).isNotEmpty();
    assertThat(service.snapshotHistory(13L, "REFERRERS", null, null, false).days()).isEqualTo(14);
  }

  private static TrafficDaily day(
      long repositoryId, LocalDate date, int views, int visitors, int clones, int uniqueCloners) {
    return new TrafficDaily(1L, repositoryId, date, views, visitors, clones, uniqueCloners);
  }
}
