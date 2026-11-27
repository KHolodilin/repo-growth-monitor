package com.kholodilin.repogrowth.traffic.service;

import com.kholodilin.repogrowth.catalog.service.RepositoryHealthService;
import com.kholodilin.repogrowth.catalog.service.ActivityClassifier;
import com.kholodilin.repogrowth.common.model.period.DashboardPeriod;
import com.kholodilin.repogrowth.common.model.GrowthMath;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.kholodilin.repogrowth.catalog.service.DashboardResponse;
import com.kholodilin.repogrowth.catalog.service.DashboardResponse.MetricValue;
import com.kholodilin.repogrowth.catalog.service.DashboardResponse.TrafficCoverage;
import com.kholodilin.repogrowth.catalog.service.DashboardResponse.TrafficNotice;
import com.kholodilin.repogrowth.catalog.service.DashboardResponse.TrafficPoint;
import com.kholodilin.repogrowth.catalog.model.ActivityStatus;
import com.kholodilin.repogrowth.catalog.model.GitHubOwner;
import com.kholodilin.repogrowth.catalog.model.Repository;
import com.kholodilin.repogrowth.catalog.model.RepositorySignals;
import com.kholodilin.repogrowth.catalog.service.RepositoryHealthService.DiscoverabilityScore;
import com.kholodilin.repogrowth.catalog.service.RepositoryService;
import com.kholodilin.repogrowth.collection.model.CollectionJob;
import com.kholodilin.repogrowth.collection.model.CollectionJobType;
import com.kholodilin.repogrowth.collection.model.CollectionRun;
import com.kholodilin.repogrowth.collection.planner.PlanningWindow;
import com.kholodilin.repogrowth.collection.service.CollectionRunMapper;
import com.kholodilin.repogrowth.collection.service.CollectionTrafficProbe;
import com.kholodilin.repogrowth.common.api.ApiException;
import com.kholodilin.repogrowth.traffic.model.PathSnapshot;
import com.kholodilin.repogrowth.traffic.model.PathRow;
import com.kholodilin.repogrowth.traffic.model.ReferrerRow;
import com.kholodilin.repogrowth.traffic.model.ReferrerSnapshot;
import com.kholodilin.repogrowth.traffic.model.SnapshotKind;
import com.kholodilin.repogrowth.traffic.model.TrafficDaily;
import com.kholodilin.repogrowth.traffic.model.TrafficTotals;
import com.kholodilin.repogrowth.traffic.repository.TrafficJdbcRepository;
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
public class AdminTrafficService {

  private static final Logger log = LoggerFactory.getLogger(AdminTrafficService.class);

  /** Compatibility limit for legacy days requests; period requests read the saved archive. */
  private static final int MAX_HISTORY_DAYS = 14;

  private final RepositoryService repositoryService;
  private final TrafficJdbcRepository trafficJdbcRepository;
  private final CollectionTrafficProbe collections;
  private final CollectionRunMapper collectionRunMapper;
  private final PlanningWindow planningWindow;
  private final Clock clock;

  public AdminTrafficService(
      RepositoryService repositoryService,
      TrafficJdbcRepository trafficJdbcRepository,
      CollectionTrafficProbe collections,
      CollectionRunMapper collectionRunMapper,
      PlanningWindow planningWindow,
      Clock clock) {
    this.repositoryService = repositoryService;
    this.trafficJdbcRepository = trafficJdbcRepository;
    this.collections = collections;
    this.collectionRunMapper = collectionRunMapper;
    this.planningWindow = planningWindow;
    this.clock = clock;
  }

  public RepositoryTrafficSnapshot traffic(
      long repositoryId, String periodParam, boolean includeServicePaths) {
    Repository repository = repositoryService.get(repositoryId);
    GitHubOwner owner = repositoryService.owner(repository.ownerId());
    DashboardPeriod period = periodForRepository(repositoryId, periodParam);
    List<TrafficDaily> history = trafficJdbcRepository.history(repositoryId, period.from());
    Set<Long> scope = Set.of(repositoryId);
    LocalDate end = trafficKpiEnd(scope, period);
    LocalDate previousFrom = period.allTime() ? null : comparisonFrom(period, end);
    TrafficTotals current = trafficJdbcRepository.totalsInRange(repositoryId, period.from(), end);
    TrafficTotals previous =
        period.allTime()
            ? new TrafficTotals(0, 0, 0, 0)
            : trafficJdbcRepository.totalsInRange(repositoryId, previousFrom, period.previousTo());
    TrafficCoverage coverage = coverage(scope, period.from(), end);
    TrafficCoverage previousCoverage =
        period.allTime() ? null : coverage(scope, previousFrom, period.previousTo());
    RepositoryTrafficTotals totals =
        new RepositoryTrafficTotals(
            metric(current.views(), previous.views(), period, coverage, previousCoverage),
            metric(
                current.uniqueVisitors(),
                previous.uniqueVisitors(),
                period,
                coverage,
                previousCoverage),
            metric(current.clones(), previous.clones(), period, coverage, previousCoverage),
            metric(
                current.uniqueCloners(),
                previous.uniqueCloners(),
                period,
                coverage,
                previousCoverage));
    CollectionRun latestRun = collections.latestForRepository(repositoryId).orElse(null);
    ReferrerSnapshot referrers = trafficJdbcRepository.latestReferrerSnapshot(repositoryId);
    PathSnapshot paths =
        trafficJdbcRepository.latestPathSnapshot(repositoryId, includeServicePaths);
    return new RepositoryTrafficSnapshot(
        repository,
        owner,
        period.value(),
        totals,
        repositorySeries(
            period,
            history.stream()
                .filter(day -> !day.trafficDate().isAfter(publishedEnd(period.to())))
                .toList()),
        referrers.rows(),
        referrers.snapshotAt(),
        paths.rows(),
        paths.snapshotAt(),
        latestRun == null
            ? null
            : collectionRunMapper.toResponse(collectionRunMapper.toView(latestRun)),
        trafficGap(repositoryId));
  }

  public SnapshotHistoryResponse snapshotHistory(
      long repositoryId, String kindParam, Integer daysParam, boolean includeServicePaths) {
    return snapshotHistory(repositoryId, kindParam, null, daysParam, includeServicePaths);
  }

  public SnapshotHistoryResponse snapshotHistory(
      long repositoryId,
      String kindParam,
      String periodParam,
      Integer daysParam,
      boolean includeServicePaths) {
    SnapshotKind kind = snapshotKind(kindParam);
    LocalDate today = LocalDate.now(clock);
    LocalDate from;
    LocalDate to;
    if (periodParam == null) {
      to = today;
      from = to.minusDays(historyDays(daysParam) - 1L);
    } else {
      LocalDate earliest = null;
      if ("all".equals(DashboardPeriod.normalize(periodParam))) {
        earliest =
            (kind == SnapshotKind.PATHS
                    ? trafficJdbcRepository.earliestPathSnapshotDate(
                        repositoryId, includeServicePaths)
                    : trafficJdbcRepository.earliestReferrerSnapshotDate(repositoryId))
                .orElse(today);
      }
      DashboardPeriod period = DashboardPeriod.of(periodParam, today, earliest);
      from = period.from();
      to = period.to();
    }
    int days = Math.toIntExact(java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1);
    List<SnapshotHistoryMath.Observation> snapshots =
        kind == SnapshotKind.PATHS
            ? trafficJdbcRepository.pathSnapshotsForDelta(
                repositoryId, from, to, includeServicePaths)
            : trafficJdbcRepository.referrerSnapshotsForDelta(repositoryId, from, to);
    SnapshotHistoryMath.Result history = SnapshotHistoryMath.pivot(snapshots, from, to);
    return new SnapshotHistoryResponse(
        repositoryId,
        kind.name(),
        days,
        from,
        to,
        history.dates(),
        history.rows().stream().map(AdminTrafficService::toHistoryRow).toList());
  }

  private static SnapshotHistoryRow toHistoryRow(SnapshotHistoryMath.Row row) {
    return new SnapshotHistoryRow(
        row.key(),
        row.title(),
        row.cells().stream()
            .map(cell ->
                new SnapshotHistoryCell(
                    cell.date(),
                    cell.visitors(),
                    cell.views(),
                    cell.visitorsDelta(),
                    cell.viewsDelta(),
                    cell.firstSeen()))
            .toList());
  }

  private static SnapshotKind snapshotKind(String raw) {
    if (raw == null || raw.isBlank()) {
      return SnapshotKind.REFERRERS;
    }
    String normalized = raw.trim().toUpperCase(Locale.ROOT);
    if (SnapshotKind.REFERRERS.name().equals(normalized)) {
      return SnapshotKind.REFERRERS;
    }
    if (SnapshotKind.PATHS.name().equals(normalized)) {
      return SnapshotKind.PATHS;
    }
    throw ApiException.validation("Unknown snapshot kind: " + raw);
  }

  private static int historyDays(Integer days) {
    if (days == null) {
      return MAX_HISTORY_DAYS;
    }
    return Math.min(MAX_HISTORY_DAYS, Math.max(1, days));
  }


  private DashboardPeriod periodForRepository(long repositoryId, String periodParam) {
    LocalDate today = LocalDate.now(clock);
    String normalized = DashboardPeriod.normalize(periodParam);
    LocalDate earliest = null;
    if ("all".equals(normalized)) {
      earliest =
          trafficJdbcRepository.history(repositoryId, null).stream()
              .map(TrafficDaily::trafficDate)
              .min(LocalDate::compareTo)
              .orElse(null);
      LocalDate earliestReferrer =
          trafficJdbcRepository.earliestReferrerSnapshotDate(repositoryId).orElse(null);
      if (earliest == null) {
        earliest = earliestReferrer;
      } else if (earliestReferrer != null && earliestReferrer.isBefore(earliest)) {
        earliest = earliestReferrer;
      }
      if (earliest == null) {
        earliest = today;
      }
    }
    return DashboardPeriod.of(periodParam, today, earliest);
  }

  private static List<TrafficChartPoint> repositorySeries(
      DashboardPeriod period, List<TrafficDaily> history) {
    return history.stream()
        .filter(day -> !day.trafficDate().isAfter(period.to()))
        .map(day ->
            new TrafficChartPoint(
                day.trafficDate(),
                (long) day.views(),
                (long) day.uniqueVisitors(),
                (long) day.clones(),
                (long) day.uniqueCloners()))
        .toList();
  }

  private TrafficNotice trafficGap(long repositoryId) {
    LocalDate today = LocalDate.now(clock);
    CollectionRun todayRun = collections.find(repositoryId, today).orElse(null);
    CollectionJob trafficJob = null;
    if (todayRun != null) {
      for (CollectionJob job : collections.findByRun(todayRun.id())) {
        if (job.jobType() == CollectionJobType.TRAFFIC) {
          trafficJob = job;
        }
      }
    }
    boolean yesterdayStored =
        trafficJdbcRepository
            .latestDate(repositoryId)
            .filter(date -> !date.isBefore(today.minusDays(1)))
            .isPresent();
    TrafficCompleteness.Notice notice =
        TrafficCompleteness.status(
            today,
            planningWindow.isOpen(),
            yesterdayStored,
            TrafficCompleteness.jobState(trafficJob));
    if (notice == null) {
      return null;
    }
    return new TrafficNotice(notice.reason().name(), notice.date(), notice.lastTrafficDate());
  }

  private TrafficCoverage coverage(Set<Long> ids, LocalDate from, LocalDate to) {
    LocalDate end = publishedEnd(to);
    long days = Math.max(0, java.time.temporal.ChronoUnit.DAYS.between(from, end) + 1);
    return new TrafficCoverage(
        trafficJdbcRepository.observedDays(ids, from, end), days * ids.size());
  }

  private LocalDate publishedEnd(LocalDate to) {
    LocalDate yesterday = LocalDate.now(clock).minusDays(1);
    return to.isBefore(yesterday) ? to : yesterday;
  }

  private LocalDate trafficKpiEnd(Set<Long> ids, DashboardPeriod period) {
    LocalDate end = publishedEnd(period.to());
    if (period.allTime()
        || "1d".equals(period.value())
        || ids.isEmpty()
        || end.isBefore(period.from())) {
      return end;
    }
    if (trafficJdbcRepository.observedDays(ids, end, end) < ids.size()) {
      LocalDate prior = end.minusDays(1);
      return prior.isBefore(period.from()) ? end : prior;
    }
    return end;
  }

  private LocalDate comparisonFrom(DashboardPeriod period) {
    return comparisonFrom(period, publishedEnd(period.to()));
  }

  private LocalDate comparisonFrom(DashboardPeriod period, LocalDate end) {
    long days = java.time.temporal.ChronoUnit.DAYS.between(period.from(), end) + 1;
    return period.previousTo().minusDays(Math.max(0, days - 1));
  }

  static boolean comparable(TrafficCoverage current, TrafficCoverage previous) {
    return current.complete()
        && previous != null
        && previous.complete()
        && current.expectedDays() == previous.expectedDays();
  }

  static MetricValue metric(
      long current,
      long previous,
      DashboardPeriod period,
      TrafficCoverage coverage,
      TrafficCoverage previousCoverage) {
    Double growth =
        !period.allTime() && comparable(coverage, previousCoverage)
            ? GrowthMath.percent(current, previous)
            : null;
    return new MetricValue(
        coverage.observedDays() == 0 ? null : current, growth, coverage, previousCoverage);
  }

  public record SnapshotHistoryResponse(
      long repositoryId,
      String kind,
      int days,
      LocalDate from,
      LocalDate to,
      List<LocalDate> dates,
      List<SnapshotHistoryRow> rows) {}

  public record SnapshotHistoryRow(String key, String title, List<SnapshotHistoryCell> cells) {}

  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record SnapshotHistoryCell(
      LocalDate date,
      Integer visitors,
      Integer views,
      Integer visitorsDelta,
      Integer viewsDelta,
      boolean firstSeen) {}

  public record RepositoryTrafficTotals(
      MetricValue views,
      MetricValue uniqueVisitors,
      MetricValue clones,
      MetricValue uniqueCloners) {}

  public record RepositoryTrafficSnapshot(
      Repository repository,
      GitHubOwner owner,
      String period,
      RepositoryTrafficTotals totals,
      List<TrafficChartPoint> traffic,
      List<ReferrerRow> referrers,
      Instant referrerSnapshotAt,
      List<PathRow> paths,
      Instant pathSnapshotAt,
      CollectionRunMapper.CollectionRunResponse lastCollection,
      TrafficNotice trafficGap) {}

  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record TrafficChartPoint(
      LocalDate date, Long views, Long uniqueVisitors, Long clones, Long uniqueCloners) {}
}
