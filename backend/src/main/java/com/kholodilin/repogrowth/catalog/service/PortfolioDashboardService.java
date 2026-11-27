package com.kholodilin.repogrowth.catalog.service;

import com.kholodilin.repogrowth.traffic.service.TrafficCompleteness;
import com.kholodilin.repogrowth.common.model.period.DashboardPeriod;
import com.kholodilin.repogrowth.common.model.GrowthMath;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.kholodilin.repogrowth.catalog.service.DashboardResponse;
import com.kholodilin.repogrowth.catalog.service.DashboardResponse.ActiveCollection;
import com.kholodilin.repogrowth.catalog.service.DashboardResponse.CollectionWarning;
import com.kholodilin.repogrowth.catalog.service.DashboardResponse.DashboardState;
import com.kholodilin.repogrowth.catalog.service.DashboardResponse.DashboardSummary;
import com.kholodilin.repogrowth.catalog.service.DashboardResponse.Highlights;
import com.kholodilin.repogrowth.catalog.service.DashboardResponse.JobStatus;
import com.kholodilin.repogrowth.catalog.service.DashboardResponse.MetricValue;
import com.kholodilin.repogrowth.catalog.service.DashboardResponse.PortfolioStats;
import com.kholodilin.repogrowth.catalog.service.DashboardResponse.RepositoryRow;
import com.kholodilin.repogrowth.catalog.service.DashboardResponse.StarsMetric;
import com.kholodilin.repogrowth.catalog.service.DashboardResponse.StatsPoint;
import com.kholodilin.repogrowth.catalog.service.DashboardResponse.StatsTotal;
import com.kholodilin.repogrowth.catalog.service.DashboardResponse.TrafficCoverage;
import com.kholodilin.repogrowth.catalog.service.DashboardResponse.TrafficLeader;
import com.kholodilin.repogrowth.catalog.service.DashboardResponse.TrafficNotice;
import com.kholodilin.repogrowth.catalog.service.DashboardResponse.TrafficPoint;
import com.kholodilin.repogrowth.catalog.model.ActivityStatus;
import com.kholodilin.repogrowth.catalog.model.GitHubOwner;
import com.kholodilin.repogrowth.catalog.model.Repository;
import com.kholodilin.repogrowth.catalog.model.RepositoryVisibility;
import com.kholodilin.repogrowth.catalog.model.RepositorySignals;
import com.kholodilin.repogrowth.collection.model.CollectionJob;
import com.kholodilin.repogrowth.collection.model.CollectionJobType;
import com.kholodilin.repogrowth.collection.model.CollectionRun;
import com.kholodilin.repogrowth.collection.model.CollectionRunStatus;
import com.kholodilin.repogrowth.collection.planner.PlanningWindow;
import com.kholodilin.repogrowth.collection.repository.CollectionJobJdbcRepository;
import com.kholodilin.repogrowth.collection.repository.CollectionRunJdbcRepository;
import com.kholodilin.repogrowth.collection.service.CollectionRunMapper;
import com.kholodilin.repogrowth.common.api.ApiException;
import com.kholodilin.repogrowth.traffic.model.SnapshotKind;
import com.kholodilin.repogrowth.traffic.model.TrafficDaily;
import com.kholodilin.repogrowth.stats.model.RepositoryStatDay;
import com.kholodilin.repogrowth.stats.repository.StatsJdbcRepository;
import com.kholodilin.repogrowth.traffic.repository.TrafficJdbcRepository;
import com.kholodilin.repogrowth.traffic.repository.TrafficJdbcRepository.PathSnapshot;
import com.kholodilin.repogrowth.traffic.repository.TrafficJdbcRepository.ReferrerSnapshot;
import com.kholodilin.repogrowth.traffic.repository.TrafficJdbcRepository.RepositoryDayTotals;
import com.kholodilin.repogrowth.traffic.repository.TrafficJdbcRepository.RepositoryPeriodTotals;
import com.kholodilin.repogrowth.traffic.repository.TrafficJdbcRepository.TrafficTotals;
import com.kholodilin.repogrowth.traffic.service.SnapshotHistoryMath;
import com.kholodilin.repogrowth.traffic.service.SnapshotHistoryMath.Observation;
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
public class PortfolioDashboardService {

  private static final Logger log = LoggerFactory.getLogger(PortfolioDashboardService.class);

  private final RepositoryService repositoryService;
  private final TrafficJdbcRepository trafficJdbcRepository;
  private final StatsJdbcRepository statsJdbcRepository;
  private final CollectionRunJdbcRepository runRepository;
  private final CollectionJobJdbcRepository jobRepository;
  private final CollectionRunMapper collectionRunMapper;
  private final ActivityClassifier activityClassifier;
  private final RepositoryHealthService healthService;
  private final PortfolioHighlightsService highlightsService;
  private final PlanningWindow planningWindow;
  private final Clock clock;

  public PortfolioDashboardService(
      RepositoryService repositoryService,
      TrafficJdbcRepository trafficJdbcRepository,
      StatsJdbcRepository statsJdbcRepository,
      CollectionRunJdbcRepository runRepository,
      CollectionJobJdbcRepository jobRepository,
      CollectionRunMapper collectionRunMapper,
      ActivityClassifier activityClassifier,
      RepositoryHealthService healthService,
      PortfolioHighlightsService highlightsService,
      PlanningWindow planningWindow,
      Clock clock) {
    this.repositoryService = repositoryService;
    this.trafficJdbcRepository = trafficJdbcRepository;
    this.statsJdbcRepository = statsJdbcRepository;
    this.runRepository = runRepository;
    this.jobRepository = jobRepository;
    this.collectionRunMapper = collectionRunMapper;
    this.activityClassifier = activityClassifier;
    this.healthService = healthService;
    this.highlightsService = highlightsService;
    this.planningWindow = planningWindow;
    this.clock = clock;
  }

  public DashboardResponse dashboard(String periodParam, boolean includePrivate) {
    return dashboard(periodParam, null, includePrivate);
  }

  public DashboardResponse dashboard(String periodParam, String ownerLogin, boolean includePrivate) {
    LocalDate today = LocalDate.now(clock);
    DashboardPeriod period =
        DashboardPeriod.of(
            periodParam, today, trafficJdbcRepository.earliestTrackedTrafficDate().orElse(null));
    List<Repository> tracked = visibleTracked(repositoryService.tracked(), includePrivate);
    if (ownerLogin != null && !ownerLogin.isBlank()) {
      String needle = ownerLogin.trim().toLowerCase(Locale.ROOT) + "/";
      tracked =
          tracked.stream()
              .filter(repository ->
                  repository.fullName() != null
                      && repository.fullName().toLowerCase(Locale.ROOT).startsWith(needle))
              .toList();
    }
    if (tracked.isEmpty()) {
      return empty(period, DashboardState.NO_REPOSITORIES, 0, 0);
    }
    if (!trafficJdbcRepository.hasTrackedTraffic()) {
      return firstCollection(period, tracked);
    }
    return ready(period, tracked, includePrivate);
  }

  public PortfolioSnapshot portfolio(String period, boolean includePrivate) {
    LocalDate from = fromDate(period);
    List<Repository> tracked = visibleTracked(repositoryService.tracked(), includePrivate);
    long stars = tracked.stream().mapToLong(Repository::stars).sum();
    List<RepositoryMetrics> rows =
        tracked.stream()
            .map(repository -> {
              TrafficTotals repoTotals = trafficJdbcRepository.totals(repository.id(), from);
              return new RepositoryMetrics(
                  repository.id(),
                  repository.fullName(),
                  repoTotals.uniqueVisitors(),
                  repoTotals.views(),
                  repoTotals.clones(),
                  repository.stars());
            })
            .toList();
    long views = rows.stream().mapToLong(RepositoryMetrics::views).sum();
    long visitors = rows.stream().mapToLong(RepositoryMetrics::visitors).sum();
    long clones = rows.stream().mapToLong(RepositoryMetrics::clones).sum();
    long uniqueCloners =
        tracked.stream()
            .mapToLong(
                repository -> trafficJdbcRepository.totals(repository.id(), from).uniqueCloners())
            .sum();
    return new PortfolioSnapshot(
        tracked.size(), views, visitors, clones, uniqueCloners, stars, rows);
  }

  private static List<Repository> visibleTracked(List<Repository> tracked, boolean includePrivate) {
    if (includePrivate) {
      return tracked;
    }
    return tracked.stream()
        .filter(repository -> RepositoryVisibility.isPublic(repository.visibility()))
        .toList();
  }

  private TrafficTotals sumVisibleTotals(
      LocalDate fromInclusive, LocalDate toInclusive, Set<Long> repositoryIds) {
    long views = 0;
    long uniqueVisitors = 0;
    long clones = 0;
    long uniqueCloners = 0;
    for (Long repositoryId : repositoryIds) {
      TrafficTotals totals =
          trafficJdbcRepository.totalsInRange(repositoryId, fromInclusive, toInclusive);
      views += totals.views();
      uniqueVisitors += totals.uniqueVisitors();
      clones += totals.clones();
      uniqueCloners += totals.uniqueCloners();
    }
    return new TrafficTotals(views, uniqueVisitors, clones, uniqueCloners);
  }

  private DashboardResponse ready(
      DashboardPeriod period, List<Repository> tracked, boolean includePrivate) {
    Set<Long> visibleIds = tracked.stream().map(Repository::id).collect(Collectors.toSet());
    // Owner-scoped Portfolio is a subset; never use the global portfolio totals shortcut.
    boolean fullPortfolio =
        includePrivate
            && tracked.size() == visibleTracked(repositoryService.tracked(), true).size();
    // Match chart honesty: incomplete Portfolio T−1 is not part of KPI totals or vs-prev.
    LocalDate end = trafficKpiEnd(visibleIds, period);
    LocalDate previousFrom = period.allTime() ? null : comparisonFrom(period, end);
    TrafficCoverage coverage = coverage(visibleIds, period.from(), end);
    TrafficCoverage previousCoverage =
        period.allTime() ? null : coverage(visibleIds, previousFrom, period.previousTo());
    TrafficTotals current =
        fullPortfolio
            ? trafficJdbcRepository.portfolioTotalsInRange(period.from(), end)
            : sumVisibleTotals(period.from(), end, visibleIds);
    TrafficTotals previous =
        period.allTime()
            ? new TrafficTotals(0, 0, 0, 0)
            : fullPortfolio
                ? trafficJdbcRepository.portfolioTotalsInRange(previousFrom, period.previousTo())
                : sumVisibleTotals(previousFrom, period.previousTo(), visibleIds);
    long starsTotal = tracked.stream().mapToLong(Repository::stars).sum();
    Long starsChange =
        period.allTime() ? null : statsJdbcRepository.starsChangeSince(period.from());
    List<TrafficPoint> traffic = series(period, tracked);
    Map<Long, TrafficNotice> notices = trafficNotices(period, tracked);
    Map<Long, Long> previousVisitors = previousVisitorsByRepo(period);
    List<CollectionRun> latestRuns =
        runRepository.latestPerTrackedRepository().stream()
            .filter(run -> visibleIds.contains(run.repositoryId()))
            .toList();
    Map<Long, CollectionRun> runByRepo =
        latestRuns.stream()
            .collect(
                Collectors.toMap(CollectionRun::repositoryId, run -> run, (left, right) -> left));
    Map<Long, List<CollectionJob>> jobsByRun =
        jobRepository.findByRunIds(latestRuns.stream().map(CollectionRun::id).toList()).stream()
            .collect(Collectors.groupingBy(CollectionJob::collectionRunId));
    Map<Long, RepositoryHealthService.DiscoverabilityScore> discoverability = discoverabilityByRepo(tracked);
    Map<Long, RepositorySignals> signals =
        repositoryService.signalsFor(tracked.stream().map(Repository::id).toList());
    List<RepositoryRow> rows =
        trafficJdbcRepository
            .totalsByTrackedRepository(period.from(), publishedEnd(period.to()))
            .stream()
            .filter(row -> visibleIds.contains(row.repositoryId()))
            .map(row ->
                toRow(
                    row,
                    period,
                    previousVisitors,
                    runByRepo,
                    jobsByRun,
                    discoverability,
                    signals,
                    notices))
            .toList();
    int partialCount =
        (int)
            latestRuns.stream().filter(run -> run.status() == CollectionRunStatus.PARTIAL).count();
    return new DashboardResponse(
        period.value(),
        period.from(),
        period.to(),
        runRepository.latestCompletedAtForTracked(),
        DashboardState.READY,
        null,
        collectionWarning(partialCount),
        activeCollection(latestRuns),
        new DashboardSummary(
            tracked.size(),
            metric(current.views(), previous.views(), period, coverage, previousCoverage),
            metric(
                current.uniqueVisitors(),
                previous.uniqueVisitors(),
                period,
                coverage,
                previousCoverage),
            metric(
                current.uniqueCloners(),
                previous.uniqueCloners(),
                period,
                coverage,
                previousCoverage),
            new StarsMetric(starsTotal, starsChange)),
        traffic,
        rows,
        highlightsService.build(period, rows),
        portfolioStats(period, tracked));
  }

  private DashboardResponse firstCollection(DashboardPeriod period, List<Repository> tracked) {
    List<CollectionRun> latestRuns = runRepository.latestPerTrackedRepository();
    Map<Long, CollectionRun> runByRepo =
        latestRuns.stream()
            .collect(
                Collectors.toMap(
                    CollectionRun::repositoryId, Function.identity(), (left, right) -> left));
    Map<Long, List<CollectionJob>> jobsByRun =
        jobRepository.findByRunIds(latestRuns.stream().map(CollectionRun::id).toList()).stream()
            .collect(Collectors.groupingBy(CollectionJob::collectionRunId));
    long starsTotal = tracked.stream().mapToLong(Repository::stars).sum();
    Map<Long, RepositoryHealthService.DiscoverabilityScore> discoverability = discoverabilityByRepo(tracked);
    Map<Long, RepositorySignals> signals =
        repositoryService.signalsFor(tracked.stream().map(Repository::id).toList());
    List<RepositoryRow> rows =
        tracked.stream()
            .map(repository -> {
              CollectionRun run = runByRepo.get(repository.id());
              List<JobStatus> jobs =
                  run == null
                      ? List.of()
                      : jobsByRun.getOrDefault(run.id(), List.of()).stream()
                          .map(PortfolioDashboardService::jobStatus)
                          .toList();
              Instant activityAt = repository.activityAt();
              return new RepositoryRow(
                  repository.id(),
                  repository.fullName(),
                  null,
                  null,
                  null,
                  repository.stars(),
                  repository.forks(),
                  null,
                  run == null ? null : run.status().name(),
                  jobs,
                  repository.archived(),
                  activityAt,
                  activityClassifier.classify(repository.archived(), activityAt).name(),
                  score(discoverability.get(repository.id())).passed(),
                  score(discoverability.get(repository.id())).total(),
                  accessible(signals.get(repository.id())),
                  trafficMissing(signals.get(repository.id())),
                  null);
            })
            .toList();
    return new DashboardResponse(
        period.value(),
        period.from(),
        period.to(),
        runRepository.latestCompletedAtForTracked(),
        DashboardState.FIRST_COLLECTION,
        null,
        collectionWarning(
            (int)
                latestRuns.stream()
                    .filter(run -> run.status() == CollectionRunStatus.PARTIAL)
                    .count()),
        activeCollection(latestRuns),
        new DashboardSummary(
            tracked.size(),
            new MetricValue(null, null, null, null),
            new MetricValue(null, null, null, null),
            new MetricValue(null, null, null, null),
            new StarsMetric(starsTotal, null)),
        List.of(),
        rows,
        highlightsService.build(period, rows),
        PortfolioStats.empty());
  }

  private static DashboardResponse empty(
      DashboardPeriod period, DashboardState state, int repositories, long stars) {
    return new DashboardResponse(
        period.value(),
        period.from(),
        period.to(),
        null,
        state,
        null,
        null,
        null,
        new DashboardSummary(
            repositories,
            new MetricValue(0, null),
            new MetricValue(0, null),
            new MetricValue(0, null),
            new StarsMetric(stars, null)),
        List.of(),
        List.of(),
        Highlights.none(),
        PortfolioStats.empty());
  }

  private Map<Long, Long> previousVisitorsByRepo(DashboardPeriod period) {
    if (period.allTime()) {
      return Map.of();
    }
    return trafficJdbcRepository
        .totalsByTrackedRepository(comparisonFrom(period), period.previousTo())
        .stream()
        .collect(
            Collectors.toMap(
                RepositoryPeriodTotals::repositoryId, RepositoryPeriodTotals::uniqueVisitors));
  }

  private RepositoryRow toRow(
      RepositoryPeriodTotals row,
      DashboardPeriod period,
      Map<Long, Long> previousVisitors,
      Map<Long, CollectionRun> runByRepo,
      Map<Long, List<CollectionJob>> jobsByRun,
      Map<Long, RepositoryHealthService.DiscoverabilityScore> discoverability,
      Map<Long, RepositorySignals> signals,
      Map<Long, TrafficNotice> notices) {
    TrafficCoverage coverage = coverage(Set.of(row.repositoryId()), period.from(), period.to());
    TrafficCoverage previousCoverage =
        period.allTime()
            ? null
            : coverage(Set.of(row.repositoryId()), comparisonFrom(period), period.previousTo());
    Double growth =
        comparable(coverage, previousCoverage)
            ? GrowthMath.percent(
                row.uniqueVisitors(), previousVisitors.getOrDefault(row.repositoryId(), 0L))
            : null;
    CollectionRun run = runByRepo.get(row.repositoryId());
    List<JobStatus> jobs =
        run == null
            ? List.of()
            : jobsByRun.getOrDefault(run.id(), List.of()).stream()
                .map(PortfolioDashboardService::jobStatus)
                .toList();
    Instant activityAt = row.lastCommitAt() != null ? row.lastCommitAt() : row.githubPushedAt();
    ActivityStatus activityStatus = activityClassifier.classify(row.archived(), activityAt);
    return new RepositoryRow(
        row.repositoryId(),
        row.fullName(),
        coverage.observedDays() == 0 ? null : row.uniqueVisitors(),
        coverage.observedDays() == 0 ? null : row.views(),
        coverage.observedDays() == 0 ? null : row.clones(),
        row.stars(),
        row.forks(),
        growth,
        run == null ? null : run.status().name(),
        jobs,
        row.archived(),
        activityAt,
        activityStatus.name(),
        score(discoverability.get(row.repositoryId())).passed(),
        score(discoverability.get(row.repositoryId())).total(),
        accessible(signals.get(row.repositoryId())),
        trafficMissing(signals.get(row.repositoryId())),
        notices.get(row.repositoryId()));
  }

  static boolean accessible(RepositorySignals signals) {
    return signals == null || signals.accessible();
  }

  static boolean trafficMissing(RepositorySignals signals) {
    return signals != null && signals.trafficPermissionMissing();
  }

  private Map<Long, RepositoryHealthService.DiscoverabilityScore> discoverabilityByRepo(List<Repository> tracked) {
    Map<Long, RepositoryHealthService.DiscoverabilityScore> scores = new HashMap<>();
    for (Repository repository : tracked) {
      scores.put(repository.id(), healthService.discoverabilityScore(repository));
    }
    return scores;
  }

  private static RepositoryHealthService.DiscoverabilityScore score(RepositoryHealthService.DiscoverabilityScore score) {
    return score == null ? new RepositoryHealthService.DiscoverabilityScore(null, null) : score;
  }

  private List<TrafficPoint> series(DashboardPeriod period, List<Repository> tracked) {
    Set<String> names = tracked.stream().map(Repository::fullName).collect(Collectors.toSet());
    Map<LocalDate, List<RepositoryDayTotals>> byDate = new TreeMap<>();
    for (RepositoryDayTotals row :
        trafficJdbcRepository.dailyByRepository(period.from(), period.to())) {
      if (!names.contains(row.fullName())) continue;
      byDate.computeIfAbsent(row.date(), date -> new ArrayList<>()).add(row);
    }
    return portfolioSeries(
        period.from(), period.to(), LocalDate.now(clock), tracked.size(), byDate);
  }

  static List<TrafficPoint> portfolioSeries(
      LocalDate from,
      LocalDate to,
      LocalDate today,
      int tracked,
      Map<LocalDate, List<RepositoryDayTotals>> byDate) {
    LocalDate expectedEnd = to.isBefore(today.minusDays(1)) ? to : today.minusDays(1);
    LocalDate first = null;
    LocalDate last = null;
    for (var entry : byDate.entrySet()) {
      LocalDate day = entry.getKey();
      if (day.isBefore(from) || day.isAfter(expectedEnd)) {
        continue;
      }
      if (entry.getValue().isEmpty()) {
        continue;
      }
      if (first == null || day.isBefore(first)) {
        first = day;
      }
      if (last == null || day.isAfter(last)) {
        last = day;
      }
    }
    if (first == null) {
      return List.of();
    }
    List<TrafficPoint> points = new ArrayList<>();
    for (LocalDate day = first; !day.isAfter(last); day = day.plusDays(1)) {
      List<RepositoryDayTotals> rows = byDate.getOrDefault(day, List.of());
      if (rows.isEmpty()) {
        points.add(
            new TrafficPoint(
                day, null, null, null, null, List.of(), List.of(), List.of(), tracked));
        continue;
      }
      long views = 0;
      long visitors = 0;
      long clones = 0;
      long uniqueCloners = 0;
      Set<String> names = new HashSet<>();
      for (RepositoryDayTotals row : rows) {
        views += row.views();
        visitors += row.uniqueVisitors();
        clones += row.clones();
        uniqueCloners += row.uniqueCloners();
        names.add(row.fullName());
      }
      int missing = Math.max(0, tracked - names.size());
      points.add(
          new TrafficPoint(
              day,
              views,
              visitors,
              clones,
              uniqueCloners,
              topShares(rows, RepositoryDayTotals::views),
              topShares(rows, RepositoryDayTotals::uniqueVisitors),
              topShares(rows, RepositoryDayTotals::clones),
              missing));
    }
    return points;
  }

  private PortfolioStats portfolioStats(DashboardPeriod period, List<Repository> tracked) {
    Set<Long> visibleIds = tracked.stream().map(Repository::id).collect(Collectors.toSet());
    int trackedCount = tracked.size();
    Map<LocalDate, List<RepositoryStatDay>> byDate = new TreeMap<>();
    for (RepositoryStatDay row :
        statsJdbcRepository.trackedDailyStats(period.from(), period.to())) {
      if (!visibleIds.contains(row.repositoryId())) {
        continue;
      }
      byDate.computeIfAbsent(row.date(), date -> new ArrayList<>()).add(row);
    }
    List<StatsPoint> points =
        portfolioStatsSeries(
            period.from(), period.to(), LocalDate.now(clock), trackedCount, byDate);
    // Per-repo tip on or before the period end (same honesty as public owner Stats / 1d).
    // Absolute latest() would pull today's unfinished tip into a 1d window that ends yesterday.
    List<RepositoryStatDay> latest =
        statsJdbcRepository.latestTrackedDailyStatsOnOrBefore(period.to()).stream()
            .filter(row -> visibleIds.contains(row.repositoryId()))
            .toList();
    return portfolioStats(latest, period.allTime(), trackedCount, points);
  }

  static List<StatsPoint> portfolioStatsSeries(
      LocalDate from,
      LocalDate to,
      LocalDate today,
      int tracked,
      Map<LocalDate, List<RepositoryStatDay>> byDate) {
    LocalDate expectedEnd = to.isBefore(today.minusDays(1)) ? to : today.minusDays(1);
    LocalDate first = null;
    LocalDate last = null;
    for (var entry : byDate.entrySet()) {
      LocalDate day = entry.getKey();
      if (day.isBefore(from) || day.isAfter(expectedEnd) || entry.getValue().isEmpty()) {
        continue;
      }
      if (first == null || day.isBefore(first)) {
        first = day;
      }
      if (last == null || day.isAfter(last)) {
        last = day;
      }
    }
    if (first == null) {
      return List.of();
    }
    List<StatsPoint> points = new ArrayList<>();
    for (LocalDate day = first; !day.isAfter(last); day = day.plusDays(1)) {
      List<RepositoryStatDay> rows = byDate.getOrDefault(day, List.of());
      if (rows.isEmpty()) {
        points.add(new StatsPoint(day, null, null, null, null, tracked));
        continue;
      }
      int stars = 0;
      int forks = 0;
      int watchers = 0;
      Set<Long> ids = new HashSet<>();
      for (RepositoryStatDay row : rows) {
        stars += row.stars();
        forks += row.forks();
        watchers += row.watchers();
        ids.add(row.repositoryId());
      }
      Integer uniqueContributors = uniqueContributorCount(rows);
      points.add(
          new StatsPoint(
              day,
              stars,
              forks,
              watchers,
              uniqueContributors,
              topStatShares(rows, RepositoryStatDay::stars),
              topStatShares(rows, RepositoryStatDay::forks),
              topStatShares(rows, RepositoryStatDay::watchers),
              topUniqueContributorShares(rows),
              Math.max(0, tracked - ids.size())));
    }
    return points;
  }

  static PortfolioStats portfolioStats(
      List<RepositoryStatDay> latest, boolean allTime, List<StatsPoint> points) {
    return portfolioStats(latest, allTime, latest.size(), points);
  }

  static PortfolioStats portfolioStats(
      List<RepositoryStatDay> latest, boolean allTime, int tracked, List<StatsPoint> points) {
    List<StatsPoint> present = points.stream().filter(point -> point.stars() != null).toList();
    List<StatsPoint> uniquePresent =
        points.stream().filter(point -> point.contributors() != null).toList();
    boolean comparable = !allTime && present.size() >= 2;
    boolean uniqueComparable = !allTime && uniquePresent.size() >= 2;
    StatsPoint first = comparable ? present.getFirst() : null;
    StatsPoint last = comparable ? present.getLast() : null;
    StatsPoint uniqueFirst = uniqueComparable ? uniquePresent.getFirst() : null;
    StatsPoint uniqueLast = uniqueComparable ? uniquePresent.getLast() : null;
    Integer unique = uniqueContributorCount(latest);
    boolean uniqueComplete = unique != null && contributorsComplete(latest, tracked);
    return new PortfolioStats(
        new DashboardResponse.StatsTotals(
            portfolioLevel(
                sum(latest, RepositoryStatDay::stars),
                comparable,
                pointValue(first == null ? null : first.stars()),
                pointValue(last == null ? null : last.stars())),
            portfolioLevel(
                sum(latest, RepositoryStatDay::forks),
                comparable,
                pointValue(first == null ? null : first.forks()),
                pointValue(last == null ? null : last.forks())),
            portfolioLevel(
                sum(latest, RepositoryStatDay::watchers),
                comparable,
                pointValue(first == null ? null : first.watchers()),
                pointValue(last == null ? null : last.watchers())),
            portfolioLevel(
                unique,
                uniqueComparable,
                pointValue(uniqueFirst == null ? null : uniqueFirst.contributors()),
                pointValue(uniqueLast == null ? null : uniqueLast.contributors()),
                unique == null ? null : uniqueComplete)),
        points);
  }

  static boolean contributorsComplete(List<RepositoryStatDay> latest, int tracked) {
    if (latest.size() < tracked) {
      return false;
    }
    for (RepositoryStatDay row : latest) {
      if (row.contributorLogins() == null) {
        return false;
      }
      if (row.contributors() != row.contributorLogins().size()) {
        return false;
      }
    }
    return true;
  }

  static Integer uniqueContributorCount(List<RepositoryStatDay> rows) {
    boolean anyCollected = false;
    Set<String> logins = new HashSet<>();
    for (RepositoryStatDay row : rows) {
      if (row.contributorLogins() == null) {
        continue;
      }
      anyCollected = true;
      logins.addAll(row.contributorLogins());
    }
    return anyCollected ? logins.size() : null;
  }

  static List<TrafficLeader> topUniqueContributorShares(List<RepositoryStatDay> rows) {
    List<RepositoryStatDay> withLogins =
        rows.stream().filter(row -> row.contributorLogins() != null).toList();
    if (withLogins.isEmpty()) {
      return List.of();
    }
    return topStatShares(withLogins, row -> row.contributorLogins().size());
  }

  private static Integer sum(
      List<RepositoryStatDay> rows, ToIntFunction<RepositoryStatDay> metric) {
    if (rows.isEmpty()) {
      return null;
    }
    int total = 0;
    for (RepositoryStatDay row : rows) {
      total += metric.applyAsInt(row);
    }
    return total;
  }

  private static int pointValue(Integer value) {
    return value == null ? 0 : value;
  }

  private static StatsTotal portfolioLevel(
      Integer latest, boolean comparable, int baseline, int current) {
    return portfolioLevel(latest, comparable, baseline, current, null);
  }

  private static StatsTotal portfolioLevel(
      Integer latest, boolean comparable, int baseline, int current, Boolean complete) {
    if (!comparable) {
      return new StatsTotal(latest, null, null, complete);
    }
    return new StatsTotal(
        latest, current - baseline, GrowthMath.percent(current, baseline), complete);
  }

  static List<TrafficLeader> topShares(
      List<RepositoryDayTotals> rows, ToLongFunction<RepositoryDayTotals> metric) {
    List<TrafficLeader> shares = new ArrayList<>();
    for (RepositoryDayTotals row : rows) {
      long value = metric.applyAsLong(row);
      if (value <= 0) {
        continue;
      }
      shares.add(new TrafficLeader(row.fullName(), value));
    }
    return rankLeaders(shares);
  }

  static List<TrafficLeader> topStatShares(
      List<RepositoryStatDay> rows, ToIntFunction<RepositoryStatDay> metric) {
    List<TrafficLeader> shares = new ArrayList<>();
    for (RepositoryStatDay row : rows) {
      long value = metric.applyAsInt(row);
      if (value <= 0) {
        continue;
      }
      shares.add(new TrafficLeader(row.fullName(), value));
    }
    return rankLeaders(shares);
  }

  private static List<TrafficLeader> rankLeaders(List<TrafficLeader> shares) {
    shares.sort(
        Comparator.comparingLong(TrafficLeader::value)
            .reversed()
            .thenComparing(TrafficLeader::fullName));
    return List.copyOf(shares.subList(0, Math.min(3, shares.size())));
  }

  static <T> List<T> dropTrailingGaps(List<T> points, Function<T, Long> views) {
    int end = points.size();
    while (end > 0 && views.apply(points.get(end - 1)) == null) {
      end--;
    }
    return end == points.size() ? points : new ArrayList<>(points.subList(0, end));
  }

  private Map<Long, TrafficNotice> trafficNotices(
      DashboardPeriod period, List<Repository> tracked) {
    LocalDate today = LocalDate.now(clock);
    boolean windowOpen = planningWindow.isOpen();
    Map<Long, Set<LocalDate>> stored = new HashMap<>();
    for (TrafficJdbcRepository.StoredTrafficDay day :
        trafficJdbcRepository.trackedTrafficDays(period.from(), period.to())) {
      stored.computeIfAbsent(day.repositoryId(), id -> new HashSet<>()).add(day.date());
    }
    Map<Long, LocalDate> latest = new HashMap<>();
    for (TrafficJdbcRepository.LatestTrafficDate day :
        trafficJdbcRepository.latestTrackedTrafficDates()) {
      if (day.date() != null) {
        latest.put(day.repositoryId(), day.date());
      }
    }
    Map<Long, CollectionJob> trafficJobs = new HashMap<>();
    List<Long> runIds = runRepository.trackedOn(today).stream().map(CollectionRun::id).toList();
    for (CollectionJob job : jobRepository.findByRunIds(runIds)) {
      if (job.jobType() == CollectionJobType.TRAFFIC) {
        trafficJobs.put(job.repositoryId(), job);
      }
    }
    Map<Long, TrafficNotice> notices = new HashMap<>();
    for (Repository repository : tracked) {
      TrafficCompleteness.Notice notice =
          TrafficCompleteness.portfolio(
              today,
              windowOpen,
              period.from(),
              period.to(),
              stored.getOrDefault(repository.id(), Set.of()),
              latest.get(repository.id()),
              TrafficCompleteness.jobState(trafficJobs.get(repository.id())));
      if (notice == null) {
        continue;
      }
      notices.put(
          repository.id(),
          new TrafficNotice(notice.reason().name(), notice.date(), notice.lastTrafficDate()));
    }
    return notices;
  }

  private TrafficNotice trafficGap(long repositoryId) {
    LocalDate today = LocalDate.now(clock);
    CollectionRun todayRun = runRepository.find(repositoryId, today).orElse(null);
    CollectionJob trafficJob = null;
    if (todayRun != null) {
      for (CollectionJob job : jobRepository.findByRun(todayRun.id())) {
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

  private static CollectionWarning collectionWarning(int partialCount) {
    if (partialCount <= 0) {
      return null;
    }
    String message =
        partialCount == 1
            ? "1 repository has partial collection"
            : partialCount + " repositories have partial collection";
    return new CollectionWarning(partialCount, message);
  }

  static JobStatus jobStatus(CollectionJob job) {
    return new JobStatus(job.jobType().name(), job.status().name(), job.errorMessage());
  }

  private static ActiveCollection activeCollection(List<CollectionRun> latestRuns) {
    CollectionRun active =
        latestRuns.stream()
            .filter(run ->
                run.status() == CollectionRunStatus.RUNNING
                    || run.status() == CollectionRunStatus.PLANNED)
            .findFirst()
            .orElse(null);
    if (active == null) {
      return null;
    }
    return new ActiveCollection(
        active.status().name(), active.successfulJobs(), active.plannedJobs());
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

  private LocalDate fromDate(String period) {
    LocalDate today = LocalDate.now(clock);
    if (period == null || period.isBlank() || "all".equalsIgnoreCase(period)) {
      return null;
    }
    return switch (period.toLowerCase()) {
      case "1d" -> today.minusDays(1);
      case "7d" -> today.minusDays(6);
      case "30d" -> today.minusDays(29);
      case "90d" -> today.minusDays(89);
      case "1y" -> today.minusYears(1).plusDays(1);
      default -> today.minusDays(29);
    };
  }

  public record PortfolioSnapshot(
      int repositories,
      long views,
      long visitors,
      long clones,
      long uniqueCloners,
      long stars,
      List<RepositoryMetrics> table) {}

  public record RepositoryMetrics(
      long id, String fullName, long visitors, long views, long clones, int stars) {}
}
