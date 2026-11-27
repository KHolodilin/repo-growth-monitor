package com.kholodilin.repogrowth.traffic.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.kholodilin.repogrowth.administration.service.PublicationService;
import com.kholodilin.repogrowth.analytics.application.DashboardPeriod;
import com.kholodilin.repogrowth.analytics.application.GrowthMath;
import com.kholodilin.repogrowth.auth.security.ResourceAccessPolicy;
import com.kholodilin.repogrowth.catalog.model.Repository;
import com.kholodilin.repogrowth.catalog.service.RepositoryService;
import com.kholodilin.repogrowth.common.api.ApiException;
import com.kholodilin.repogrowth.common.api.PeriodParser;
import com.kholodilin.repogrowth.common.model.kpi.Coverage;
import com.kholodilin.repogrowth.common.model.kpi.DataStatus;
import com.kholodilin.repogrowth.common.model.kpi.KpiChange;
import com.kholodilin.repogrowth.common.model.kpi.KpiScope;
import com.kholodilin.repogrowth.common.model.kpi.KpiValue;
import com.kholodilin.repogrowth.common.model.kpi.Unit;
import com.kholodilin.repogrowth.common.model.period.ComparisonRange;
import com.kholodilin.repogrowth.common.model.period.DateRange;
import com.kholodilin.repogrowth.common.model.period.Period;
import com.kholodilin.repogrowth.common.model.period.PeriodRanges;
import com.kholodilin.repogrowth.traffic.SnapshotHistoryMath;
import com.kholodilin.repogrowth.traffic.SnapshotHistoryMath.Observation;
import com.kholodilin.repogrowth.traffic.SnapshotKind;
import com.kholodilin.repogrowth.traffic.api.TrafficKpiResponse;
import com.kholodilin.repogrowth.traffic.domain.TrafficDaily;
import com.kholodilin.repogrowth.traffic.persistence.TrafficJdbcRepository;
import com.kholodilin.repogrowth.traffic.persistence.TrafficJdbcRepository.PathSnapshot;
import com.kholodilin.repogrowth.traffic.persistence.TrafficJdbcRepository.ReferrerSnapshot;
import com.kholodilin.repogrowth.traffic.persistence.TrafficJdbcRepository.TrafficTotals;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Resource-API Traffic reads: typed KPI, daily series, referrers/paths snapshots and rolling-window
 * histories. Owner aggregates include only repositories the caller may read for the Traffic module.
 */
@Service
public class TrafficReadService {

  /** Compatibility ceiling for legacy {@code days} requests; period requests use the archive. */
  private static final int MAX_HISTORY_DAYS = 14;

  private final ResourceAccessPolicy access;
  private final RepositoryService repositories;
  private final TrafficJdbcRepository traffic;
  private final Clock clock;

  public TrafficReadService(
      ResourceAccessPolicy access,
      RepositoryService repositories,
      TrafficJdbcRepository traffic,
      Clock clock) {
    this.access = access;
    this.repositories = repositories;
    this.traffic = traffic;
    this.clock = clock;
  }

  public TrafficKpiResponse repositoryKpi(long repositoryId, String periodParam) {
    access.requireReadable(repositoryId, PublicationService.Module.TRAFFIC);
    return kpi(
        KpiScope.REPOSITORY, Long.toString(repositoryId), List.of(repositoryId), periodParam);
  }

  public TrafficKpiResponse ownerKpi(long ownerId, String periodParam) {
    List<Long> readable = readableOwnerRepositories(ownerId, PublicationService.Module.TRAFFIC);
    if (readable.isEmpty()) {
      throw ApiException.notFound("Owner not found");
    }
    return kpi(KpiScope.OWNER, Long.toString(ownerId), readable, periodParam);
  }

  public DailyHistoryResponse dailyHistory(long repositoryId, String periodParam) {
    access.requireReadable(repositoryId, PublicationService.Module.TRAFFIC);
    DashboardPeriod period = periodForTraffic(List.of(repositoryId), periodParam);
    LocalDate end = publishedEnd(period.to());
    List<TrafficDaily> history = traffic.history(repositoryId, period.from());
    List<DailyPoint> points =
        history.stream()
            .filter(day -> !day.trafficDate().isAfter(end))
            .map(day ->
                new DailyPoint(
                    day.trafficDate(),
                    (long) day.views(),
                    (long) day.uniqueVisitors(),
                    (long) day.clones(),
                    (long) day.uniqueCloners()))
            .toList();
    return new DailyHistoryResponse(
        KpiScope.REPOSITORY,
        Long.toString(repositoryId),
        PeriodParser.parse(period.value()),
        new DateRange(period.from(), period.to()),
        points);
  }

  public DailyHistoryResponse ownerDailyHistory(long ownerId, String periodParam) {
    List<Long> readable = readableOwnerRepositories(ownerId, PublicationService.Module.TRAFFIC);
    if (readable.isEmpty()) {
      throw ApiException.notFound("Owner not found");
    }
    DashboardPeriod period = periodForTraffic(readable, periodParam);
    LocalDate end = publishedEnd(period.to());
    LocalDate from = period.from();
    // Aggregate per day across readable repositories; missing repo-days stay incomplete, not
    // zero-filled.
    java.util.Map<LocalDate, long[]> byDate = new java.util.TreeMap<>();
    java.util.Map<LocalDate, Integer> observed = new java.util.HashMap<>();
    for (Long repositoryId : readable) {
      for (TrafficDaily day : traffic.history(repositoryId, from)) {
        if (day.trafficDate().isAfter(end)) {
          continue;
        }
        long[] bucket =
            byDate.computeIfAbsent(day.trafficDate(), ignored -> new long[] {0, 0, 0, 0});
        bucket[0] += day.views();
        bucket[1] += day.uniqueVisitors();
        bucket[2] += day.clones();
        bucket[3] += day.uniqueCloners();
        observed.merge(day.trafficDate(), 1, Integer::sum);
      }
    }
    List<DailyPoint> points = new ArrayList<>();
    for (var entry : byDate.entrySet()) {
      long[] v = entry.getValue();
      boolean complete = observed.getOrDefault(entry.getKey(), 0) == readable.size();
      points.add(
          new DailyPoint(
              entry.getKey(),
              complete ? v[0] : null,
              complete ? v[1] : null,
              complete ? v[2] : null,
              complete ? v[3] : null));
    }
    return new DailyHistoryResponse(
        KpiScope.OWNER,
        Long.toString(ownerId),
        PeriodParser.parse(period.value()),
        new DateRange(period.from(), period.to()),
        points);
  }

  public SnapshotResponse latestReferrers(long repositoryId) {
    access.requireReadable(repositoryId, PublicationService.Module.REFERRERS);
    ReferrerSnapshot snapshot = traffic.latestReferrerSnapshot(repositoryId);
    return new SnapshotResponse(
        Long.toString(repositoryId),
        SnapshotKind.REFERRERS.name(),
        snapshot.snapshotAt(),
        snapshot.rows().stream()
            .map(row -> new SnapshotRow(row.referrer(), null, row.views(), row.uniqueVisitors()))
            .toList());
  }

  public SnapshotResponse latestPaths(long repositoryId, boolean includeServicePaths) {
    access.requireReadable(repositoryId, PublicationService.Module.PATHS);
    PathSnapshot snapshot = traffic.latestPathSnapshot(repositoryId, includeServicePaths);
    return new SnapshotResponse(
        Long.toString(repositoryId),
        SnapshotKind.PATHS.name(),
        snapshot.snapshotAt(),
        snapshot.rows().stream()
            .map(row -> new SnapshotRow(row.path(), row.title(), row.views(), row.uniqueVisitors()))
            .toList());
  }

  public SnapshotHistoryResponse referrerHistory(
      long repositoryId, String periodParam, Integer days) {
    access.requireReadable(repositoryId, PublicationService.Module.REFERRERS);
    return snapshotHistory(repositoryId, SnapshotKind.REFERRERS, periodParam, days, false);
  }

  public SnapshotHistoryResponse pathHistory(
      long repositoryId, String periodParam, Integer days, boolean includeServicePaths) {
    access.requireReadable(repositoryId, PublicationService.Module.PATHS);
    return snapshotHistory(
        repositoryId, SnapshotKind.PATHS, periodParam, days, includeServicePaths);
  }

  /**
   * Legacy admin bridge: {@code kind} still accepted until clients move to dedicated
   * referrer-history / path-history paths.
   */
  public SnapshotHistoryResponse snapshotHistory(
      long repositoryId,
      String kindParam,
      String periodParam,
      Integer daysParam,
      boolean includeServicePaths) {
    SnapshotKind kind = snapshotKind(kindParam);
    PublicationService.Module module =
        kind == SnapshotKind.PATHS
            ? PublicationService.Module.PATHS
            : PublicationService.Module.REFERRERS;
    access.requireReadable(repositoryId, module);
    return snapshotHistory(repositoryId, kind, periodParam, daysParam, includeServicePaths);
  }

  private SnapshotHistoryResponse snapshotHistory(
      long repositoryId,
      SnapshotKind kind,
      String periodParam,
      Integer daysParam,
      boolean includeServicePaths) {
    LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
    LocalDate from;
    LocalDate to;
    if (periodParam == null || periodParam.isBlank()) {
      to = today;
      from = to.minusDays(historyDays(daysParam) - 1L);
    } else {
      LocalDate earliest = null;
      if ("all".equals(DashboardPeriod.normalize(periodParam))) {
        earliest =
            (kind == SnapshotKind.PATHS
                    ? traffic.earliestPathSnapshotDate(repositoryId, includeServicePaths)
                    : traffic.earliestReferrerSnapshotDate(repositoryId))
                .orElse(today);
      }
      DashboardPeriod period = DashboardPeriod.of(periodParam, today, earliest);
      from = period.from();
      to = period.to();
    }
    int days = Math.toIntExact(java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1);
    List<Observation> snapshots =
        kind == SnapshotKind.PATHS
            ? traffic.pathSnapshotsForDelta(repositoryId, from, to, includeServicePaths)
            : traffic.referrerSnapshotsForDelta(repositoryId, from, to);
    SnapshotHistoryMath.Result history = SnapshotHistoryMath.pivot(snapshots, from, to);
    return new SnapshotHistoryResponse(
        Long.toString(repositoryId),
        kind.name(),
        days,
        from,
        to,
        history.dates(),
        history.rows().stream().map(TrafficReadService::toHistoryRow).toList());
  }

  private TrafficKpiResponse kpi(
      KpiScope scope, String scopeId, List<Long> repositoryIds, String periodParam) {
    Period resolved = PeriodParser.parse(periodParam);
    DashboardPeriod period = periodForTraffic(repositoryIds, periodParam);
    Set<Long> scopeIds = new HashSet<>(repositoryIds);
    LocalDate end = trafficKpiEnd(scopeIds, period);
    LocalDate previousFrom = period.allTime() ? null : comparisonFrom(period, end);
    TrafficTotals current = sumTotals(repositoryIds, period.from(), end);
    TrafficTotals previous =
        period.allTime()
            ? new TrafficTotals(0, 0, 0, 0)
            : sumTotals(repositoryIds, previousFrom, period.previousTo());
    Coverage coverage = coverage(scopeIds, period.from(), end);
    Coverage previousCoverage =
        period.allTime() ? null : coverage(scopeIds, previousFrom, period.previousTo());
    boolean comparable =
        !period.allTime()
            && coverage.expected() > 0
            && coverage.observed() == coverage.expected()
            && previousCoverage != null
            && previousCoverage.observed() == previousCoverage.expected()
            && coverage.expected() == previousCoverage.expected();
    DateRange range = new DateRange(period.from(), period.to());
    ComparisonRange comparison =
        resolved.allTime() ? null : PeriodRanges.comparison(range).orElse(null);
    return new TrafficKpiResponse(
        scope,
        scopeId,
        resolved,
        range,
        comparison,
        new TrafficKpiResponse.Metrics(
            kpiValue(current.views(), previous.views(), comparable, coverage),
            kpiValue(current.uniqueVisitors(), previous.uniqueVisitors(), comparable, coverage),
            kpiValue(current.clones(), previous.clones(), comparable, coverage),
            kpiValue(current.uniqueCloners(), previous.uniqueCloners(), comparable, coverage)));
  }

  private static KpiValue kpiValue(
      long current, long previous, boolean comparable, Coverage coverage) {
    if (coverage.observed() == 0) {
      return new KpiValue(null, Unit.COUNT, DataStatus.MISSING, null, coverage, null);
    }
    DataStatus status =
        coverage.expected() > 0 && coverage.observed() == coverage.expected()
            ? DataStatus.COMPLETE
            : DataStatus.PARTIAL;
    KpiChange change = null;
    if (comparable) {
      Double percent = GrowthMath.percent(current, previous);
      change =
          new KpiChange(
              BigDecimal.valueOf(current - previous),
              percent == null ? null : BigDecimal.valueOf(percent));
    }
    return new KpiValue(BigDecimal.valueOf(current), Unit.COUNT, status, change, coverage, null);
  }

  private TrafficTotals sumTotals(List<Long> repositoryIds, LocalDate from, LocalDate to) {
    long views = 0;
    long uniqueVisitors = 0;
    long clones = 0;
    long uniqueCloners = 0;
    for (Long repositoryId : repositoryIds) {
      TrafficTotals totals = traffic.totalsInRange(repositoryId, from, to);
      views += totals.views();
      uniqueVisitors += totals.uniqueVisitors();
      clones += totals.clones();
      uniqueCloners += totals.uniqueCloners();
    }
    return new TrafficTotals(views, uniqueVisitors, clones, uniqueCloners);
  }

  private Coverage coverage(Set<Long> ids, LocalDate from, LocalDate to) {
    LocalDate end = publishedEnd(to);
    long days = Math.max(0, java.time.temporal.ChronoUnit.DAYS.between(from, end) + 1);
    return new Coverage(traffic.observedDays(ids, from, end), days * ids.size(), "repository-days");
  }

  private LocalDate trafficKpiEnd(Set<Long> ids, DashboardPeriod period) {
    LocalDate end = publishedEnd(period.to());
    if (period.allTime()
        || "1d".equals(period.value())
        || ids.isEmpty()
        || end.isBefore(period.from())) {
      return end;
    }
    if (traffic.observedDays(ids, end, end) < ids.size()) {
      LocalDate prior = end.minusDays(1);
      return prior.isBefore(period.from()) ? end : prior;
    }
    return end;
  }

  private LocalDate comparisonFrom(DashboardPeriod period, LocalDate end) {
    long days = java.time.temporal.ChronoUnit.DAYS.between(period.from(), end) + 1;
    return period.previousTo().minusDays(Math.max(0, days - 1));
  }

  private LocalDate publishedEnd(LocalDate to) {
    LocalDate yesterday = LocalDate.now(clock.withZone(ZoneOffset.UTC)).minusDays(1);
    return to.isBefore(yesterday) ? to : yesterday;
  }

  private DashboardPeriod periodForTraffic(List<Long> repositoryIds, String periodParam) {
    LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
    String normalized = DashboardPeriod.normalize(periodParam);
    LocalDate earliest = null;
    if ("all".equals(normalized)) {
      for (Long repositoryId : repositoryIds) {
        LocalDate repoEarliest =
            traffic.history(repositoryId, null).stream()
                .map(TrafficDaily::trafficDate)
                .min(LocalDate::compareTo)
                .orElse(null);
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

  private List<Long> readableOwnerRepositories(long ownerId, PublicationService.Module module) {
    repositories.owner(ownerId);
    List<Long> owned =
        repositories.list(false).stream()
            .filter(repo -> repo.ownerId() == ownerId)
            .map(Repository::id)
            .toList();
    return access.filterReadable(owned, module);
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

  public record DailyHistoryResponse(
      KpiScope scope, String scopeId, Period period, DateRange range, List<DailyPoint> points) {}

  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record DailyPoint(
      LocalDate date, Long views, Long visitors, Long clones, Long uniqueCloners) {}

  public record SnapshotResponse(
      String repositoryId, String kind, Instant snapshotAt, List<SnapshotRow> rows) {}

  public record SnapshotRow(String key, String title, int views, int uniqueVisitors) {}

  public record SnapshotHistoryResponse(
      String repositoryId,
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
}
