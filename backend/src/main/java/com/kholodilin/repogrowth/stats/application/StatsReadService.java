package com.kholodilin.repogrowth.stats.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.kholodilin.repogrowth.analytics.application.DashboardPeriod;
import com.kholodilin.repogrowth.analytics.application.GrowthMath;
import com.kholodilin.repogrowth.auth.security.ResourceAccessPolicy;
import com.kholodilin.repogrowth.common.api.ApiException;
import com.kholodilin.repogrowth.common.api.PeriodParser;
import com.kholodilin.repogrowth.common.model.kpi.DataStatus;
import com.kholodilin.repogrowth.common.model.kpi.KpiChange;
import com.kholodilin.repogrowth.common.model.kpi.KpiScope;
import com.kholodilin.repogrowth.common.model.kpi.KpiValue;
import com.kholodilin.repogrowth.common.model.kpi.Unit;
import com.kholodilin.repogrowth.common.model.period.ComparisonRange;
import com.kholodilin.repogrowth.common.model.period.DateRange;
import com.kholodilin.repogrowth.common.model.period.Period;
import com.kholodilin.repogrowth.common.model.period.PeriodRanges;
import com.kholodilin.repogrowth.publication.PublicationService;
import com.kholodilin.repogrowth.repository.application.RepositoryService;
import com.kholodilin.repogrowth.repository.domain.Repository;
import com.kholodilin.repogrowth.stats.api.StatsKpiResponse;
import com.kholodilin.repogrowth.traffic.persistence.TrafficJdbcRepository;
import com.kholodilin.repogrowth.traffic.persistence.TrafficJdbcRepository.RepositoryDailyStats;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import org.springframework.stereotype.Service;

/**
 * Resource-API Stats reads. Stars/forks/watchers/contributors are tip levels at period end — not
 * summed traffic-style windows. Owner aggregates only include readable repositories.
 */
@Service
public class StatsReadService {

  private final ResourceAccessPolicy access;
  private final RepositoryService repositories;
  private final TrafficJdbcRepository traffic;
  private final Clock clock;

  public StatsReadService(
      ResourceAccessPolicy access,
      RepositoryService repositories,
      TrafficJdbcRepository traffic,
      Clock clock) {
    this.access = access;
    this.repositories = repositories;
    this.traffic = traffic;
    this.clock = clock;
  }

  public StatsKpiResponse repositoryKpi(long repositoryId, String periodParam) {
    access.requireReadable(repositoryId, PublicationService.Module.DASHBOARD);
    History history = repositoryHistory(repositoryId, periodParam);
    return toKpi(KpiScope.REPOSITORY, Long.toString(repositoryId), history);
  }

  public StatsKpiResponse ownerKpi(long ownerId, String periodParam) {
    List<Long> readable = readableOwnerRepositories(ownerId);
    if (readable.isEmpty()) {
      throw ApiException.notFound("Owner not found");
    }
    History history = ownerHistory(ownerId, readable, periodParam);
    return toKpi(KpiScope.OWNER, Long.toString(ownerId), history);
  }

  public StatsHistoryResponse repositoryHistoryResponse(long repositoryId, String periodParam) {
    access.requireReadable(repositoryId, PublicationService.Module.DASHBOARD);
    History history = repositoryHistory(repositoryId, periodParam);
    return new StatsHistoryResponse(
        KpiScope.REPOSITORY,
        Long.toString(repositoryId),
        history.period(),
        history.range(),
        history.totals(),
        history.points());
  }

  public StatsHistoryResponse ownerHistoryResponse(long ownerId, String periodParam) {
    List<Long> readable = readableOwnerRepositories(ownerId);
    if (readable.isEmpty()) {
      throw ApiException.notFound("Owner not found");
    }
    History history = ownerHistory(ownerId, readable, periodParam);
    return new StatsHistoryResponse(
        KpiScope.OWNER,
        Long.toString(ownerId),
        history.period(),
        history.range(),
        history.totals(),
        history.points());
  }

  private History repositoryHistory(long repositoryId, String periodParam) {
    DashboardPeriod period = periodForStats(List.of(repositoryId), periodParam);
    List<RepositoryDailyStats> raw =
        traffic.dailyStatsHistory(repositoryId, period.allTime() ? null : period.from());
    List<StatsHistoryPoint> points =
        raw.stream()
            .filter(day -> !day.statDate().isAfter(period.to()))
            .map(day ->
                new StatsHistoryPoint(
                    day.statDate(), day.stars(), day.forks(), day.watchers(), day.contributors()))
            .toList();
    RepositoryDailyStats tip =
        traffic.latestDailyStatsOnOrBefore(repositoryId, period.to()).orElse(null);
    StatsTotals totals = statsTotals(tip, points, period.allTime());
    Period resolved = PeriodParser.parse(period.value());
    DateRange range = new DateRange(period.from(), period.to());
    return new History(resolved, range, totals, points);
  }

  private History ownerHistory(long ownerId, List<Long> repositoryIds, String periodParam) {
    DashboardPeriod period = periodForStats(repositoryIds, periodParam);
    TreeMap<LocalDate, int[]> byDate = new TreeMap<>();
    int tipStars = 0;
    int tipForks = 0;
    int tipWatchers = 0;
    int tipContributors = 0;
    int tipCount = 0;
    for (Long repositoryId : repositoryIds) {
      List<RepositoryDailyStats> raw =
          traffic.dailyStatsHistory(repositoryId, period.allTime() ? null : period.from());
      for (RepositoryDailyStats day : raw) {
        if (day.statDate().isAfter(period.to())) {
          continue;
        }
        int[] bucket = byDate.computeIfAbsent(day.statDate(), ignored -> new int[] {0, 0, 0, 0, 0});
        bucket[0] += day.stars();
        bucket[1] += day.forks();
        bucket[2] += day.watchers();
        bucket[3] += day.contributors();
        bucket[4] += 1;
      }
      RepositoryDailyStats tip =
          traffic.latestDailyStatsOnOrBefore(repositoryId, period.to()).orElse(null);
      if (tip != null) {
        tipStars += tip.stars();
        tipForks += tip.forks();
        tipWatchers += tip.watchers();
        tipContributors += tip.contributors();
        tipCount++;
      }
    }
    List<StatsHistoryPoint> points = new ArrayList<>();
    for (var entry : byDate.entrySet()) {
      int[] v = entry.getValue();
      boolean complete = v[4] == repositoryIds.size();
      points.add(
          new StatsHistoryPoint(
              entry.getKey(),
              complete ? v[0] : null,
              complete ? v[1] : null,
              complete ? v[2] : null,
              complete ? v[3] : null));
    }
    // Owner tip uses summed per-repo tips; publish only when every readable repo has a tip.
    StatsTotals totals =
        ownerTotals(
            tipCount == repositoryIds.size() ? tipStars : null,
            tipCount == repositoryIds.size() ? tipForks : null,
            tipCount == repositoryIds.size() ? tipWatchers : null,
            tipCount == repositoryIds.size() ? tipContributors : null,
            tipStars,
            tipForks,
            tipWatchers,
            tipContributors,
            points,
            period.allTime());
    Period resolved = PeriodParser.parse(period.value());
    DateRange range = new DateRange(period.from(), period.to());
    return new History(resolved, range, totals, points);
  }

  private StatsKpiResponse toKpi(KpiScope scope, String scopeId, History history) {
    ComparisonRange comparison =
        history.period().allTime() ? null : PeriodRanges.comparison(history.range()).orElse(null);
    return new StatsKpiResponse(
        scope,
        scopeId,
        history.period(),
        history.range(),
        comparison,
        new StatsKpiResponse.Metrics(
            toKpiValue(history.totals().stars()),
            toKpiValue(history.totals().forks()),
            toKpiValue(history.totals().watchers()),
            toKpiValue(history.totals().contributors())));
  }

  private static KpiValue toKpiValue(StatsMetricTotal metric) {
    if (metric.value() == null) {
      return new KpiValue(null, Unit.COUNT, DataStatus.MISSING, null, null, null);
    }
    KpiChange change = null;
    if (metric.absoluteChange() != null || metric.growthPercent() != null) {
      change =
          new KpiChange(
              metric.absoluteChange() == null ? null : BigDecimal.valueOf(metric.absoluteChange()),
              metric.growthPercent() == null ? null : BigDecimal.valueOf(metric.growthPercent()));
    }
    return new KpiValue(
        BigDecimal.valueOf(metric.value()), Unit.COUNT, DataStatus.COMPLETE, change, null, null);
  }

  private static StatsTotals statsTotals(
      RepositoryDailyStats latest, List<StatsHistoryPoint> points, boolean allTime) {
    boolean comparable = !allTime && points.size() >= 2;
    StatsHistoryPoint first = comparable ? points.getFirst() : null;
    StatsHistoryPoint last = comparable ? points.getLast() : null;
    return new StatsTotals(
        level(
            latest == null ? null : latest.stars(),
            first == null || first.stars() == null ? 0 : first.stars(),
            last == null || last.stars() == null ? 0 : last.stars(),
            comparable),
        level(
            latest == null ? null : latest.forks(),
            first == null || first.forks() == null ? 0 : first.forks(),
            last == null || last.forks() == null ? 0 : last.forks(),
            comparable),
        level(
            latest == null ? null : latest.watchers(),
            first == null || first.watchers() == null ? 0 : first.watchers(),
            last == null || last.watchers() == null ? 0 : last.watchers(),
            comparable),
        level(
            latest == null ? null : latest.contributors(),
            first == null || first.contributors() == null ? 0 : first.contributors(),
            last == null || last.contributors() == null ? 0 : last.contributors(),
            comparable));
  }

  private static StatsTotals ownerTotals(
      Integer publishedStars,
      Integer publishedForks,
      Integer publishedWatchers,
      Integer publishedContributors,
      int rawStars,
      int rawForks,
      int rawWatchers,
      int rawContributors,
      List<StatsHistoryPoint> points,
      boolean allTime) {
    boolean comparable = !allTime && points.size() >= 2;
    StatsHistoryPoint first = comparable ? points.getFirst() : null;
    StatsHistoryPoint last = comparable ? points.getLast() : null;
    return new StatsTotals(
        level(
            publishedStars,
            first == null || first.stars() == null ? 0 : first.stars(),
            last == null || last.stars() == null ? rawStars : last.stars(),
            comparable && publishedStars != null),
        level(
            publishedForks,
            first == null || first.forks() == null ? 0 : first.forks(),
            last == null || last.forks() == null ? rawForks : last.forks(),
            comparable && publishedForks != null),
        level(
            publishedWatchers,
            first == null || first.watchers() == null ? 0 : first.watchers(),
            last == null || last.watchers() == null ? rawWatchers : last.watchers(),
            comparable && publishedWatchers != null),
        level(
            publishedContributors,
            first == null || first.contributors() == null ? 0 : first.contributors(),
            last == null || last.contributors() == null ? rawContributors : last.contributors(),
            comparable && publishedContributors != null));
  }

  private static StatsMetricTotal level(
      Integer latest, int baseline, int current, boolean comparable) {
    if (!comparable) {
      return new StatsMetricTotal(latest, null, null);
    }
    return new StatsMetricTotal(latest, current - baseline, GrowthMath.percent(current, baseline));
  }

  private DashboardPeriod periodForStats(List<Long> repositoryIds, String periodParam) {
    LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
    String normalized = DashboardPeriod.normalize(periodParam);
    LocalDate earliest = null;
    if ("all".equals(normalized)) {
      for (Long repositoryId : repositoryIds) {
        LocalDate repoEarliest = traffic.earliestDailyStatsDate(repositoryId).orElse(null);
        if (repoEarliest != null && (earliest == null || repoEarliest.isBefore(earliest))) {
          earliest = repoEarliest;
        }
      }
      if (earliest == null) {
        earliest = today;
      }
    }
    return DashboardPeriod.of(periodParam, today, earliest);
  }

  private List<Long> readableOwnerRepositories(long ownerId) {
    repositories.owner(ownerId);
    List<Long> owned =
        repositories.list(false).stream()
            .filter(repo -> repo.ownerId() == ownerId)
            .map(Repository::id)
            .toList();
    return access.filterReadable(owned, PublicationService.Module.DASHBOARD);
  }

  private record History(
      Period period, DateRange range, StatsTotals totals, List<StatsHistoryPoint> points) {}

  public record StatsHistoryResponse(
      KpiScope scope,
      String scopeId,
      Period period,
      DateRange range,
      StatsTotals totals,
      List<StatsHistoryPoint> points) {}

  public record StatsTotals(
      StatsMetricTotal stars,
      StatsMetricTotal forks,
      StatsMetricTotal watchers,
      StatsMetricTotal contributors) {}

  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record StatsMetricTotal(Integer value, Integer absoluteChange, Double growthPercent) {}

  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record StatsHistoryPoint(
      LocalDate date, Integer stars, Integer forks, Integer watchers, Integer contributors) {}
}
