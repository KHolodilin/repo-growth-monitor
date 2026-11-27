package com.kholodilin.repogrowth.stats.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

import com.kholodilin.repogrowth.administration.service.PublicationService;
import com.kholodilin.repogrowth.catalog.model.GitHubOwner;
import com.kholodilin.repogrowth.catalog.model.OwnerType;
import com.kholodilin.repogrowth.catalog.model.Repository;
import com.kholodilin.repogrowth.catalog.service.RepositoryService;
import com.kholodilin.repogrowth.common.api.ApiException;
import com.kholodilin.repogrowth.common.model.kpi.DataStatus;
import com.kholodilin.repogrowth.common.model.kpi.KpiScope;
import com.kholodilin.repogrowth.identity.security.ResourceAccessPolicy;
import com.kholodilin.repogrowth.traffic.repository.TrafficJdbcRepository;
import com.kholodilin.repogrowth.traffic.repository.TrafficJdbcRepository.RepositoryDailyStats;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StatsReadServiceTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);
  private static final Clock CLOCK =
      Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

  @Mock ResourceAccessPolicy access;
  @Mock RepositoryService repositories;
  @Mock TrafficJdbcRepository traffic;

  StatsReadService service;
  Repository repository;
  Repository sibling;

  @BeforeEach
  void setUp() {
    service = new StatsReadService(access, repositories, traffic, CLOCK);
    Instant now = Instant.parse("2026-11-01T00:00:00Z");
    repository =
        new Repository(
            13L,
            99L,
            1L,
            "outbox",
            "acme/outbox",
            "d",
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
    sibling =
        new Repository(
            14L,
            100L,
            1L,
            "kafka",
            "acme/kafka",
            "d",
            "PUBLIC",
            "main",
            "Java",
            false,
            false,
            10,
            0,
            5,
            1,
            3,
            true,
            now,
            now,
            now,
            now,
            null,
            null,
            now,
            now);
  }

  @Test
  void repositoryKpiAndHistoryExposeTipLevelsWithComparison() {
    doNothing().when(access).requireReadable(13L, PublicationService.Module.DASHBOARD);
    LocalDate from = TODAY.minusDays(6);
    List<RepositoryDailyStats> days =
        List.of(
            stats(from, 10, 2, 1, 1),
            stats(TODAY.minusDays(1), 20, 4, 2, 2),
            stats(TODAY, 21, 4, 2, 2));
    when(traffic.dailyStatsHistory(13L, from)).thenReturn(days);
    when(traffic.latestDailyStatsOnOrBefore(13L, TODAY)).thenReturn(Optional.of(days.getLast()));
    var kpi = service.repositoryKpi(13L, "7d");
    assertThat(kpi.scope()).isEqualTo(KpiScope.REPOSITORY);
    assertThat(kpi.metrics().stars().dataStatus()).isEqualTo(DataStatus.COMPLETE);
    assertThat(kpi.metrics().stars().value()).isNotNull();
    var history = service.repositoryHistoryResponse(13L, "7d");
    assertThat(history.points()).hasSize(3);
    assertThat(history.totals().stars().absoluteChange()).isNotNull();
  }

  @Test
  void ownerAggregatesRequireEveryReadableTipAndRejectEmptyOwners() {
    when(repositories.owner(1L))
        .thenReturn(
            new GitHubOwner(
                1L,
                1L,
                "acme",
                OwnerType.USER,
                null,
                "https://github.com/acme",
                Instant.now(),
                Instant.now()));
    when(repositories.list(false)).thenReturn(List.of(repository, sibling));
    when(access.filterReadable(List.of(13L, 14L), PublicationService.Module.DASHBOARD))
        .thenReturn(List.of(13L, 14L));
    LocalDate from = TODAY.minusDays(6);
    when(traffic.dailyStatsHistory(eq(13L), any()))
        .thenReturn(List.of(stats(from, 10, 1, 1, 1), stats(TODAY, 12, 2, 1, 1)));
    when(traffic.dailyStatsHistory(eq(14L), any()))
        .thenReturn(List.of(stats(from, 5, 1, 1, 1), stats(TODAY, 6, 1, 1, 1)));
    when(traffic.latestDailyStatsOnOrBefore(eq(13L), any()))
        .thenReturn(Optional.of(stats(TODAY, 12, 2, 1, 1)));
    when(traffic.latestDailyStatsOnOrBefore(eq(14L), any()))
        .thenReturn(Optional.of(stats(TODAY, 6, 1, 1, 1)));
    var kpi = service.ownerKpi(1L, "7d");
    assertThat(kpi.scope()).isEqualTo(KpiScope.OWNER);
    assertThat(service.ownerHistoryResponse(1L, "7d").points()).isNotEmpty();

    when(access.filterReadable(List.of(13L, 14L), PublicationService.Module.DASHBOARD))
        .thenReturn(List.of());
    assertThatThrownBy(() -> service.ownerKpi(1L, "7d"))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("Owner not found");
  }

  @Test
  void allTimeUsesEarliestStatsDateAndSkipsComparison() {
    doNothing().when(access).requireReadable(13L, PublicationService.Module.DASHBOARD);
    when(traffic.earliestDailyStatsDate(13L)).thenReturn(Optional.of(TODAY.minusDays(3)));
    when(traffic.dailyStatsHistory(13L, null))
        .thenReturn(List.of(stats(TODAY.minusDays(3), 1, 0, 0, 0), stats(TODAY, 4, 1, 0, 1)));
    when(traffic.latestDailyStatsOnOrBefore(13L, TODAY))
        .thenReturn(Optional.of(stats(TODAY, 4, 1, 0, 1)));
    var kpi = service.repositoryKpi(13L, "all");
    assertThat(kpi.comparison()).isNull();
    assertThat(kpi.metrics().stars().change()).isNull();
  }

  @Test
  void missingTipIsReportedAsMissingMetric() {
    doNothing().when(access).requireReadable(13L, PublicationService.Module.DASHBOARD);
    when(traffic.dailyStatsHistory(eq(13L), any())).thenReturn(List.of());
    when(traffic.latestDailyStatsOnOrBefore(eq(13L), any())).thenReturn(Optional.empty());
    var kpi = service.repositoryKpi(13L, "7d");
    assertThat(kpi.metrics().stars().dataStatus()).isEqualTo(DataStatus.MISSING);
  }

  private static RepositoryDailyStats stats(
      LocalDate date, int stars, int forks, int watchers, int contributors) {
    return new RepositoryDailyStats(date, stars, forks, watchers, contributors);
  }
}
