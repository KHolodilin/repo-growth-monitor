package com.kholodilin.repogrowth.stats.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.kholodilin.repogrowth.common.model.kpi.DataStatus;
import com.kholodilin.repogrowth.common.model.kpi.KpiScope;
import com.kholodilin.repogrowth.stats.model.RepositoryDailyStats;
import com.kholodilin.repogrowth.stats.repository.StatsJdbcRepository;
import java.time.Clock;
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

  @Mock StatsJdbcRepository stats;

  StatsReadService service;

  @BeforeEach
  void setUp() {
    service = new StatsReadService(stats, CLOCK);
  }

  @Test
  void repositoryKpiAndHistoryExposeTipLevelsWithComparison() {
    LocalDate from = TODAY.minusDays(6);
    List<RepositoryDailyStats> days =
        List.of(
            stats(from, 10, 2, 1, 1),
            stats(TODAY.minusDays(1), 20, 4, 2, 2),
            stats(TODAY, 21, 4, 2, 2));
    when(stats.dailyStatsHistory(13L, from)).thenReturn(days);
    when(stats.latestDailyStatsOnOrBefore(13L, TODAY)).thenReturn(Optional.of(days.getLast()));
    var kpi = service.repositoryKpi(13L, "7d");
    assertThat(kpi.scope()).isEqualTo(KpiScope.REPOSITORY);
    assertThat(kpi.metrics().stars().dataStatus()).isEqualTo(DataStatus.COMPLETE);
    assertThat(kpi.metrics().stars().value()).isNotNull();
    var history = service.repositoryHistoryResponse(13L, "7d");
    assertThat(history.points()).hasSize(3);
    assertThat(history.totals().stars().absoluteChange()).isNotNull();
  }

  @Test
  void ownerAggregatesRequireEveryReadableTip() {
    LocalDate from = TODAY.minusDays(6);
    when(stats.dailyStatsHistory(eq(13L), any()))
        .thenReturn(List.of(stats(from, 10, 1, 1, 1), stats(TODAY, 12, 2, 1, 1)));
    when(stats.dailyStatsHistory(eq(14L), any()))
        .thenReturn(List.of(stats(from, 5, 1, 1, 1), stats(TODAY, 6, 1, 1, 1)));
    when(stats.latestDailyStatsOnOrBefore(eq(13L), any()))
        .thenReturn(Optional.of(stats(TODAY, 12, 2, 1, 1)));
    when(stats.latestDailyStatsOnOrBefore(eq(14L), any()))
        .thenReturn(Optional.of(stats(TODAY, 6, 1, 1, 1)));
    var kpi = service.ownerKpi(1L, List.of(13L, 14L), "7d");
    assertThat(kpi.scope()).isEqualTo(KpiScope.OWNER);
    assertThat(service.ownerHistoryResponse(1L, List.of(13L, 14L), "7d").points()).isNotEmpty();
  }

  @Test
  void allTimeUsesEarliestStatsDateAndSkipsComparison() {
    when(stats.earliestDailyStatsDate(13L)).thenReturn(Optional.of(TODAY.minusDays(3)));
    when(stats.dailyStatsHistory(13L, null))
        .thenReturn(List.of(stats(TODAY.minusDays(3), 1, 0, 0, 0), stats(TODAY, 4, 1, 0, 1)));
    when(stats.latestDailyStatsOnOrBefore(13L, TODAY))
        .thenReturn(Optional.of(stats(TODAY, 4, 1, 0, 1)));
    var kpi = service.repositoryKpi(13L, "all");
    assertThat(kpi.comparison()).isNull();
    assertThat(kpi.metrics().stars().change()).isNull();
  }

  @Test
  void missingTipIsReportedAsMissingMetric() {
    when(stats.dailyStatsHistory(eq(13L), any())).thenReturn(List.of());
    when(stats.latestDailyStatsOnOrBefore(eq(13L), any())).thenReturn(Optional.empty());
    var kpi = service.repositoryKpi(13L, "7d");
    assertThat(kpi.metrics().stars().dataStatus()).isEqualTo(DataStatus.MISSING);
  }

  private static RepositoryDailyStats stats(
      LocalDate date, int stars, int forks, int watchers, int contributors) {
    return new RepositoryDailyStats(date, stars, forks, watchers, contributors);
  }
}
