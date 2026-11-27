package com.kholodilin.repogrowth.stats.service;

import com.kholodilin.repogrowth.traffic.service.TrafficCompleteness;
import com.kholodilin.repogrowth.common.model.period.DashboardPeriod;
import com.kholodilin.repogrowth.common.model.GrowthMath;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.kholodilin.repogrowth.catalog.model.GitHubOwner;
import com.kholodilin.repogrowth.catalog.model.Repository;
import com.kholodilin.repogrowth.catalog.model.RepositoryVisibility;
import com.kholodilin.repogrowth.catalog.repository.RepositoryJdbcRepository.RepositorySignals;
import com.kholodilin.repogrowth.catalog.service.RepositoryService;
import com.kholodilin.repogrowth.common.api.ApiException;
import com.kholodilin.repogrowth.identity.security.CurrentUserAccess;
import com.kholodilin.repogrowth.traffic.model.SnapshotKind;
import com.kholodilin.repogrowth.traffic.repository.TrafficJdbcRepository;
import com.kholodilin.repogrowth.traffic.repository.TrafficJdbcRepository.RepositoryPeriodTotals;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class AdminStatsHistoryService {

  private final RepositoryService repositoryService;
  private final TrafficJdbcRepository trafficJdbcRepository;
  private final Clock clock;
  private final CurrentUserAccess currentUserAccess;

  public AdminStatsHistoryService(
      RepositoryService repositoryService,
      TrafficJdbcRepository trafficJdbcRepository,
      Clock clock,
      CurrentUserAccess currentUserAccess) {
    this.repositoryService = repositoryService;
    this.trafficJdbcRepository = trafficJdbcRepository;
    this.clock = clock;
    this.currentUserAccess = currentUserAccess;
  }

  public RepositoryStatsHistory statsHistory(long repositoryId, String periodParam) {
    requireVisible(repositoryService.get(repositoryId));
    DashboardPeriod period = periodForStats(repositoryId, periodParam);
    List<TrafficJdbcRepository.RepositoryDailyStats> history =
        trafficJdbcRepository.dailyStatsHistory(
            repositoryId, period.allTime() ? null : period.from());
    List<StatsHistoryPoint> points =
        history.stream()
            .filter(day -> !day.statDate().isAfter(period.to()))
            .map(day ->
                new StatsHistoryPoint(
                    day.statDate(), day.stars(), day.forks(), day.watchers(), day.contributors()))
            .toList();
    TrafficJdbcRepository.RepositoryDailyStats tip =
        trafficJdbcRepository.latestDailyStatsOnOrBefore(repositoryId, period.to()).orElse(null);
    return new RepositoryStatsHistory(
        repositoryId, period.value(), statsTotals(tip, points, period.allTime()), points);
  }

  private static StatsTotals statsTotals(
      TrafficJdbcRepository.RepositoryDailyStats latest,
      List<StatsHistoryPoint> points,
      boolean allTime) {
    boolean comparable = !allTime && points.size() >= 2;
    StatsHistoryPoint first = comparable ? points.getFirst() : null;
    StatsHistoryPoint last = comparable ? points.getLast() : null;
    return new StatsTotals(
        level(
            latest == null ? null : latest.stars(),
            first == null ? 0 : first.stars(),
            last == null ? 0 : last.stars(),
            comparable),
        level(
            latest == null ? null : latest.forks(),
            first == null ? 0 : first.forks(),
            last == null ? 0 : last.forks(),
            comparable),
        level(
            latest == null ? null : latest.watchers(),
            first == null ? 0 : first.watchers(),
            last == null ? 0 : last.watchers(),
            comparable),
        level(
            latest == null ? null : latest.contributors(),
            first == null ? 0 : first.contributors(),
            last == null ? 0 : last.contributors(),
            comparable));
  }

  private static StatsMetricTotal level(
      Integer latest, int baseline, int current, boolean comparable) {
    if (!comparable) {
      return new StatsMetricTotal(latest, null, null);
    }
    return new StatsMetricTotal(latest, current - baseline, GrowthMath.percent(current, baseline));
  }

  private DashboardPeriod periodForStats(long repositoryId, String periodParam) {
    LocalDate today = LocalDate.now(clock);
    String normalized = DashboardPeriod.normalize(periodParam);
    LocalDate earliest = null;
    if ("all".equals(normalized)) {
      earliest = trafficJdbcRepository.earliestDailyStatsDate(repositoryId).orElse(today);
    }
    return DashboardPeriod.of(periodParam, today, earliest);
  }

  private Repository requireVisible(Repository repository) {
    if (currentUserAccess.canSeePrivateRepositories()
        || RepositoryVisibility.isPublic(repository.visibility())) {
      return repository;
    }
    throw ApiException.notFound("Repository not found");
  }

  public record RepositoryStatsHistory(
      long repositoryId, String period, StatsTotals totals, List<StatsHistoryPoint> points) {}

  public record StatsTotals(
      StatsMetricTotal stars,
      StatsMetricTotal forks,
      StatsMetricTotal watchers,
      StatsMetricTotal contributors) {}

  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record StatsMetricTotal(Integer value, Integer absoluteChange, Double growthPercent) {}

  public record StatsHistoryPoint(
      LocalDate date, int stars, int forks, int watchers, int contributors) {}
}
