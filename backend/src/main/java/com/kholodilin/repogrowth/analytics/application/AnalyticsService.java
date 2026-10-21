package com.kholodilin.repogrowth.analytics.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.kholodilin.repogrowth.analytics.api.DashboardResponse;
import com.kholodilin.repogrowth.analytics.api.DashboardResponse.ActiveCollection;
import com.kholodilin.repogrowth.analytics.api.DashboardResponse.CollectionWarning;
import com.kholodilin.repogrowth.analytics.api.DashboardResponse.DashboardState;
import com.kholodilin.repogrowth.analytics.api.DashboardResponse.DashboardSummary;
import com.kholodilin.repogrowth.analytics.api.DashboardResponse.JobStatus;
import com.kholodilin.repogrowth.analytics.api.DashboardResponse.MetricValue;
import com.kholodilin.repogrowth.analytics.api.DashboardResponse.RepositoryRow;
import com.kholodilin.repogrowth.analytics.api.DashboardResponse.TrafficNotice;
import com.kholodilin.repogrowth.analytics.api.DashboardResponse.StarsMetric;
import com.kholodilin.repogrowth.analytics.api.DashboardResponse.TrafficLeader;
import com.kholodilin.repogrowth.analytics.api.DashboardResponse.Highlights;
import com.kholodilin.repogrowth.analytics.api.DashboardResponse.TrafficPoint;
import com.kholodilin.repogrowth.repository.application.RepositoryHealthService.DiscoverabilityScore;
import com.kholodilin.repogrowth.collection.api.CollectionController;
import com.kholodilin.repogrowth.collection.domain.CollectionJob;
import com.kholodilin.repogrowth.collection.domain.CollectionJobType;
import com.kholodilin.repogrowth.collection.domain.CollectionRun;
import com.kholodilin.repogrowth.collection.domain.CollectionRunStatus;
import com.kholodilin.repogrowth.collection.persistence.CollectionJobJdbcRepository;
import com.kholodilin.repogrowth.collection.persistence.CollectionRunJdbcRepository;
import com.kholodilin.repogrowth.repository.application.RepositoryHealthService;
import com.kholodilin.repogrowth.repository.application.RepositoryService;
import com.kholodilin.repogrowth.search.application.ActivityClassifier;
import com.kholodilin.repogrowth.search.domain.ActivityStatus;
import com.kholodilin.repogrowth.repository.domain.GitHubOwner;
import com.kholodilin.repogrowth.repository.domain.Repository;
import com.kholodilin.repogrowth.repository.persistence.RepositoryJdbcRepository.RepositorySignals;
import com.kholodilin.repogrowth.collection.planner.PlanningWindow;
import com.kholodilin.repogrowth.common.api.ApiException;
import com.kholodilin.repogrowth.traffic.SnapshotHistoryMath;
import com.kholodilin.repogrowth.traffic.SnapshotHistoryMath.Observation;
import com.kholodilin.repogrowth.traffic.SnapshotKind;
import com.kholodilin.repogrowth.traffic.domain.TrafficDaily;
import com.kholodilin.repogrowth.traffic.persistence.TrafficJdbcRepository;
import com.kholodilin.repogrowth.traffic.persistence.TrafficJdbcRepository.PathSnapshot;
import com.kholodilin.repogrowth.traffic.persistence.TrafficJdbcRepository.ReferrerSnapshot;
import com.kholodilin.repogrowth.traffic.persistence.TrafficJdbcRepository.RepositoryDayTotals;
import com.kholodilin.repogrowth.traffic.persistence.TrafficJdbcRepository.RepositoryPeriodTotals;
import com.kholodilin.repogrowth.traffic.persistence.TrafficJdbcRepository.TrafficTotals;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;

@Service
public class AnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsService.class);
    /** GitHub reports a rolling 14-day window, so a wider history table would repeat itself. */
    private static final int MAX_HISTORY_DAYS = 14;

    private final RepositoryService repositoryService;
    private final TrafficJdbcRepository trafficJdbcRepository;
    private final CollectionRunJdbcRepository runRepository;
    private final CollectionJobJdbcRepository jobRepository;
    private final CollectionController collectionController;
    private final ActivityClassifier activityClassifier;
    private final RepositoryHealthService healthService;
    private final PortfolioHighlightsService highlightsService;
    private final PlanningWindow planningWindow;
    private final Clock clock;

    public AnalyticsService(
            RepositoryService repositoryService,
            TrafficJdbcRepository trafficJdbcRepository,
            CollectionRunJdbcRepository runRepository,
            CollectionJobJdbcRepository jobRepository,
            CollectionController collectionController,
            ActivityClassifier activityClassifier,
            RepositoryHealthService healthService,
            PortfolioHighlightsService highlightsService,
            PlanningWindow planningWindow,
            Clock clock
    ) {
        this.repositoryService = repositoryService;
        this.trafficJdbcRepository = trafficJdbcRepository;
        this.runRepository = runRepository;
        this.jobRepository = jobRepository;
        this.collectionController = collectionController;
        this.activityClassifier = activityClassifier;
        this.healthService = healthService;
        this.highlightsService = highlightsService;
        this.planningWindow = planningWindow;
        this.clock = clock;
    }

    public DashboardResponse dashboard(String periodParam) {
        LocalDate today = LocalDate.now(clock);
        DashboardPeriod period = DashboardPeriod.of(
                periodParam,
                today,
                trafficJdbcRepository.earliestTrackedTrafficDate().orElse(null)
        );
        List<Repository> tracked = repositoryService.tracked();
        if (tracked.isEmpty()) {
            return empty(period, DashboardState.NO_REPOSITORIES, 0, 0);
        }
        if (!trafficJdbcRepository.hasTrackedTraffic()) {
            return firstCollection(period, tracked);
        }
        return ready(period, tracked);
    }

    public PortfolioSnapshot portfolio(String period) {
        LocalDate from = fromDate(period);
        List<Repository> tracked = repositoryService.tracked();
        TrafficTotals totals = trafficJdbcRepository.portfolioTotals(from);
        long stars = tracked.stream().mapToLong(Repository::stars).sum();
        List<RepositoryMetrics> rows = tracked.stream().map(repository -> {
            TrafficTotals repoTotals = trafficJdbcRepository.totals(repository.id(), from);
            return new RepositoryMetrics(
                    repository.id(),
                    repository.fullName(),
                    repoTotals.uniqueVisitors(),
                    repoTotals.views(),
                    repoTotals.clones(),
                    repository.stars()
            );
        }).toList();
        return new PortfolioSnapshot(
                tracked.size(),
                totals.views(),
                totals.uniqueVisitors(),
                totals.clones(),
                totals.uniqueCloners(),
                stars,
                rows
        );
    }

    public RepositoryTrafficSnapshot traffic(long repositoryId, String periodParam, boolean includeServicePaths) {
        Repository repository = repositoryService.get(repositoryId);
        GitHubOwner owner = repositoryService.owner(repository.ownerId());
        DashboardPeriod period = periodForRepository(repositoryId, periodParam);
        List<TrafficDaily> history = trafficJdbcRepository.history(repositoryId, period.from());
        TrafficTotals current = period.allTime()
                ? trafficJdbcRepository.totals(repositoryId, null)
                : trafficJdbcRepository.totalsInRange(repositoryId, period.from(), period.to());
        TrafficTotals previous = period.allTime()
                ? new TrafficTotals(0, 0, 0, 0)
                : trafficJdbcRepository.totalsInRange(repositoryId, period.previousFrom(), period.previousTo());
        RepositoryTrafficTotals totals = new RepositoryTrafficTotals(
                metric(current.views(), previous.views(), period),
                metric(current.uniqueVisitors(), previous.uniqueVisitors(), period),
                metric(current.clones(), previous.clones(), period),
                metric(current.uniqueCloners(), previous.uniqueCloners(), period)
        );
        CollectionRun latestRun = runRepository.latestForRepository(repositoryId).orElse(null);
        ReferrerSnapshot referrers = trafficJdbcRepository.latestReferrerSnapshot(repositoryId);
        PathSnapshot paths = trafficJdbcRepository.latestPathSnapshot(repositoryId, includeServicePaths);
        return new RepositoryTrafficSnapshot(
                repository,
                owner,
                period.value(),
                totals,
                repositorySeries(period, history),
                referrers.rows(),
                referrers.snapshotAt(),
                paths.rows(),
                paths.snapshotAt(),
                latestRun == null ? null : collectionController.toResponse(latestRun),
                trafficGap(repositoryId)
        );
    }

    /**
     * Stars, forks, watchers, and contributors are levels. The card value is the latest stored
     * snapshot and does not follow the selected window. The change is the last point in that
     * window minus the first. All-time, and a window with fewer than two points, has no change.
     */
    public RepositoryStatsHistory statsHistory(long repositoryId, String periodParam) {
        repositoryService.get(repositoryId);
        DashboardPeriod period = periodForStats(repositoryId, periodParam);
        List<TrafficJdbcRepository.RepositoryDailyStats> history =
                trafficJdbcRepository.dailyStatsHistory(repositoryId, period.allTime() ? null : period.from());
        List<StatsHistoryPoint> points = history.stream()
                .filter(day -> !day.statDate().isAfter(period.to()))
                .map(day -> new StatsHistoryPoint(
                        day.statDate(),
                        day.stars(),
                        day.forks(),
                        day.watchers(),
                        day.contributors()
                ))
                .toList();
        TrafficJdbcRepository.RepositoryDailyStats latest =
                trafficJdbcRepository.latestDailyStats(repositoryId).orElse(null);
        return new RepositoryStatsHistory(
                repositoryId,
                period.value(),
                statsTotals(latest, points, period.allTime()),
                points
        );
    }

    private static StatsTotals statsTotals(
            TrafficJdbcRepository.RepositoryDailyStats latest,
            List<StatsHistoryPoint> points,
            boolean allTime
    ) {
        boolean comparable = !allTime && points.size() >= 2;
        StatsHistoryPoint first = comparable ? points.getFirst() : null;
        StatsHistoryPoint last = comparable ? points.getLast() : null;
        return new StatsTotals(
                level(latest == null ? null : latest.stars(), first == null ? null : first.stars(), last == null ? null : last.stars(), comparable),
                level(latest == null ? null : latest.forks(), first == null ? null : first.forks(), last == null ? null : last.forks(), comparable),
                level(latest == null ? null : latest.watchers(), first == null ? null : first.watchers(), last == null ? null : last.watchers(), comparable),
                level(latest == null ? null : latest.contributors(), first == null ? null : first.contributors(), last == null ? null : last.contributors(), comparable)
        );
    }

    private static StatsMetricTotal level(Integer latest, Integer baseline, Integer current, boolean comparable) {
        if (!comparable || latest == null || baseline == null || current == null) {
            return new StatsMetricTotal(latest, null, null);
        }
        return new StatsMetricTotal(latest, current - baseline, GrowthMath.percent(current, baseline));
    }

    /**
     * Both the chart and the table of the history page are served from this payload, so they cannot
     * disagree about a snapshot. The chart hides negative deltas itself; the table shows them.
     */
    public SnapshotHistoryResponse snapshotHistory(
            long repositoryId,
            String kindParam,
            Integer daysParam,
            boolean includeServicePaths
    ) {
        repositoryService.get(repositoryId);
        SnapshotKind kind = snapshotKind(kindParam);
        int days = historyDays(daysParam);
        LocalDate to = LocalDate.now(clock);
        LocalDate from = to.minusDays(days - 1L);
        List<Observation> snapshots = kind == SnapshotKind.PATHS
                ? trafficJdbcRepository.pathSnapshotsForDelta(repositoryId, from, to, includeServicePaths)
                : trafficJdbcRepository.referrerSnapshotsForDelta(repositoryId, from, to);
        SnapshotHistoryMath.Result history = SnapshotHistoryMath.pivot(snapshots, from, to);
        return new SnapshotHistoryResponse(
                repositoryId,
                kind.name(),
                days,
                from,
                to,
                history.dates(),
                history.rows().stream().map(AnalyticsService::toHistoryRow).toList()
        );
    }

    private static SnapshotHistoryRow toHistoryRow(SnapshotHistoryMath.Row row) {
        return new SnapshotHistoryRow(
                row.key(),
                row.title(),
                row.cells().stream()
                        .map(cell -> new SnapshotHistoryCell(
                                cell.date(),
                                cell.visitors(),
                                cell.views(),
                                cell.visitorsDelta(),
                                cell.viewsDelta(),
                                cell.firstSeen()
                        ))
                        .toList()
        );
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

    private DashboardPeriod periodForStats(long repositoryId, String periodParam) {
        LocalDate today = LocalDate.now(clock);
        String normalized = DashboardPeriod.normalize(periodParam);
        LocalDate earliest = null;
        if ("all".equals(normalized)) {
            earliest = trafficJdbcRepository.earliestDailyStatsDate(repositoryId).orElse(today);
        }
        return DashboardPeriod.of(periodParam, today, earliest);
    }

    private DashboardPeriod periodForRepository(long repositoryId, String periodParam) {
        LocalDate today = LocalDate.now(clock);
        String normalized = DashboardPeriod.normalize(periodParam);
        LocalDate earliest = null;
        if ("all".equals(normalized)) {
            earliest = trafficJdbcRepository.history(repositoryId, null).stream()
                    .map(TrafficDaily::trafficDate)
                    .min(LocalDate::compareTo)
                    .orElse(null);
            LocalDate earliestReferrer = trafficJdbcRepository.earliestReferrerSnapshotDate(repositoryId)
                    .orElse(null);
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

    private static List<TrafficChartPoint> repositorySeries(DashboardPeriod period, List<TrafficDaily> history) {
        return history.stream()
                .filter(day -> !day.trafficDate().isAfter(period.to()))
                .map(day -> new TrafficChartPoint(
                        day.trafficDate(),
                        (long) day.views(),
                        (long) day.uniqueVisitors(),
                        (long) day.clones(),
                        (long) day.uniqueCloners()
                ))
                .toList();
    }

    private DashboardResponse ready(DashboardPeriod period, List<Repository> tracked) {
        TrafficTotals current = trafficJdbcRepository.portfolioTotalsInRange(period.from(), period.to());
        TrafficTotals previous = period.allTime()
                ? new TrafficTotals(0, 0, 0, 0)
                : trafficJdbcRepository.portfolioTotalsInRange(period.previousFrom(), period.previousTo());
        long starsTotal = tracked.stream().mapToLong(Repository::stars).sum();
        Long starsChange = period.allTime() ? null : trafficJdbcRepository.starsChangeSince(period.from());
        List<TrafficPoint> traffic = series(period, tracked.size());
        Map<Long, TrafficNotice> notices = trafficNotices(period, tracked);
        Map<Long, Long> previousVisitors = previousVisitorsByRepo(period);
        List<CollectionRun> latestRuns = runRepository.latestPerTrackedRepository();
        Map<Long, CollectionRun> runByRepo = latestRuns.stream()
                .collect(Collectors.toMap(CollectionRun::repositoryId, run -> run, (left, right) -> left));
        Map<Long, List<CollectionJob>> jobsByRun = jobRepository.findByRunIds(
                        latestRuns.stream().map(CollectionRun::id).toList()
                ).stream()
                .collect(Collectors.groupingBy(CollectionJob::collectionRunId));
        Map<Long, DiscoverabilityScore> discoverability = discoverabilityByRepo(tracked);
        Map<Long, RepositorySignals> signals = repositoryService.signalsFor(tracked.stream().map(Repository::id).toList());
        List<RepositoryRow> rows = trafficJdbcRepository.totalsByTrackedRepository(period.from(), period.to())
                .stream()
                .map(row -> toRow(row, period, previousVisitors, runByRepo, jobsByRun, discoverability, signals, notices))
                .toList();
        int partialCount = (int) latestRuns.stream()
                .filter(run -> run.status() == CollectionRunStatus.PARTIAL)
                .count();
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
                        metric(current.views(), previous.views(), period),
                        metric(current.uniqueVisitors(), previous.uniqueVisitors(), period),
                        metric(current.clones(), previous.clones(), period),
                        new StarsMetric(starsTotal, starsChange)
                ),
                traffic,
                rows,
                highlightsService.build(period, rows)
        );
    }

    private DashboardResponse firstCollection(DashboardPeriod period, List<Repository> tracked) {
        List<CollectionRun> latestRuns = runRepository.latestPerTrackedRepository();
        Map<Long, CollectionRun> runByRepo = latestRuns.stream()
                .collect(Collectors.toMap(CollectionRun::repositoryId, Function.identity(), (left, right) -> left));
        Map<Long, List<CollectionJob>> jobsByRun = jobRepository.findByRunIds(
                        latestRuns.stream().map(CollectionRun::id).toList()
                ).stream()
                .collect(Collectors.groupingBy(CollectionJob::collectionRunId));
        long starsTotal = tracked.stream().mapToLong(Repository::stars).sum();
        Map<Long, DiscoverabilityScore> discoverability = discoverabilityByRepo(tracked);
        Map<Long, RepositorySignals> signals = repositoryService.signalsFor(tracked.stream().map(Repository::id).toList());
        List<RepositoryRow> rows = tracked.stream()
                .map(repository -> {
                    CollectionRun run = runByRepo.get(repository.id());
                    List<JobStatus> jobs = run == null
                            ? List.of()
                            : jobsByRun.getOrDefault(run.id(), List.of()).stream()
                                    .map(AnalyticsService::jobStatus)
                                    .toList();
                    Instant activityAt = repository.activityAt();
                    return new RepositoryRow(
                            repository.id(),
                            repository.fullName(),
                            0,
                            0,
                            0,
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
                            null
                    );
                })
                .toList();
        return new DashboardResponse(
                period.value(),
                period.from(),
                period.to(),
                runRepository.latestCompletedAtForTracked(),
                DashboardState.FIRST_COLLECTION,
                null,
                collectionWarning((int) latestRuns.stream()
                        .filter(run -> run.status() == CollectionRunStatus.PARTIAL)
                        .count()),
                activeCollection(latestRuns),
                new DashboardSummary(
                        tracked.size(),
                        new MetricValue(0, null),
                        new MetricValue(0, null),
                        new MetricValue(0, null),
                        new StarsMetric(starsTotal, null)
                ),
                List.of(),
                rows,
                highlightsService.build(period, rows)
        );
    }

    private static DashboardResponse empty(DashboardPeriod period, DashboardState state, int repositories, long stars) {
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
                        new StarsMetric(stars, null)
                ),
                List.of(),
                List.of(),
                Highlights.none()
        );
    }

    private Map<Long, Long> previousVisitorsByRepo(DashboardPeriod period) {
        if (period.allTime()) {
            return Map.of();
        }
        return trafficJdbcRepository.totalsByTrackedRepository(period.previousFrom(), period.previousTo()).stream()
                .collect(Collectors.toMap(RepositoryPeriodTotals::repositoryId, RepositoryPeriodTotals::uniqueVisitors));
    }

    private RepositoryRow toRow(
            RepositoryPeriodTotals row,
            DashboardPeriod period,
            Map<Long, Long> previousVisitors,
            Map<Long, CollectionRun> runByRepo,
            Map<Long, List<CollectionJob>> jobsByRun,
            Map<Long, DiscoverabilityScore> discoverability,
            Map<Long, RepositorySignals> signals,
            Map<Long, TrafficNotice> notices
    ) {
        Double growth = period.allTime()
                ? null
                : GrowthMath.percent(row.uniqueVisitors(), previousVisitors.getOrDefault(row.repositoryId(), 0L));
        CollectionRun run = runByRepo.get(row.repositoryId());
        List<JobStatus> jobs = run == null
                ? List.of()
                : jobsByRun.getOrDefault(run.id(), List.of()).stream()
                        .map(AnalyticsService::jobStatus)
                        .toList();
        Instant activityAt = row.lastCommitAt() != null ? row.lastCommitAt() : row.githubPushedAt();
        ActivityStatus activityStatus = activityClassifier.classify(row.archived(), activityAt);
        return new RepositoryRow(
                row.repositoryId(),
                row.fullName(),
                row.uniqueVisitors(),
                row.views(),
                row.clones(),
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
                notices.get(row.repositoryId())
        );
    }

    static boolean accessible(RepositorySignals signals) {
        return signals == null || signals.accessible();
    }

    static boolean trafficMissing(RepositorySignals signals) {
        return signals != null && signals.trafficPermissionMissing();
    }

    private Map<Long, DiscoverabilityScore> discoverabilityByRepo(List<Repository> tracked) {
        Map<Long, DiscoverabilityScore> scores = new HashMap<>();
        for (Repository repository : tracked) {
            scores.put(repository.id(), healthService.discoverabilityScore(repository));
        }
        return scores;
    }

    private static DiscoverabilityScore score(DiscoverabilityScore score) {
        return score == null ? new DiscoverabilityScore(null, null) : score;
    }

    private List<TrafficPoint> series(DashboardPeriod period, int tracked) {
        Map<LocalDate, List<RepositoryDayTotals>> byDate = new TreeMap<>();
        for (RepositoryDayTotals row : trafficJdbcRepository.dailyByRepository(period.from(), period.to())) {
            byDate.computeIfAbsent(row.date(), date -> new ArrayList<>()).add(row);
        }
        return portfolioSeries(period.from(), period.to(), LocalDate.now(clock), tracked, byDate);
    }

    /**
     * Days nobody has are omitted after the last stored day, so the line stops.
     * A hole between stored days stays, with a null total, so the line breaks.
     * A day some repositories have is the sum of those rows and names how many are missing.
     */
    static List<TrafficPoint> portfolioSeries(
            LocalDate from,
            LocalDate to,
            LocalDate today,
            int tracked,
            Map<LocalDate, List<RepositoryDayTotals>> byDate
    ) {
        LocalDate expectedEnd = to.isBefore(today.minusDays(1)) ? to : today.minusDays(1);
        LocalDate first = null;
        LocalDate last = null;
        for (LocalDate day : byDate.keySet()) {
            if (day.isBefore(from) || day.isAfter(expectedEnd)) {
                continue;
            }
            if (byDate.get(day).isEmpty()) {
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
                points.add(new TrafficPoint(day, null, null, null, List.of(), List.of(), List.of(), tracked));
                continue;
            }
            long views = 0;
            long visitors = 0;
            long clones = 0;
            Set<String> names = new HashSet<>();
            for (RepositoryDayTotals row : rows) {
                views += row.views();
                visitors += row.uniqueVisitors();
                clones += row.clones();
                names.add(row.fullName());
            }
            int missing = Math.max(0, tracked - names.size());
            points.add(new TrafficPoint(
                    day,
                    views,
                    visitors,
                    clones,
                    topShares(rows, RepositoryDayTotals::views),
                    topShares(rows, RepositoryDayTotals::uniqueVisitors),
                    topShares(rows, RepositoryDayTotals::clones),
                    missing
            ));
        }
        return points;
    }

    /** Up to three tracked repositories with the largest positive share. Equal shares keep the earlier name. */
    static List<TrafficLeader> topShares(List<RepositoryDayTotals> rows, ToLongFunction<RepositoryDayTotals> metric) {
        List<TrafficLeader> shares = new ArrayList<>();
        for (RepositoryDayTotals row : rows) {
            long value = metric.applyAsLong(row);
            if (value <= 0) {
                continue;
            }
            shares.add(new TrafficLeader(row.fullName(), value));
        }
        shares.sort(Comparator.comparingLong(TrafficLeader::value).reversed().thenComparing(TrafficLeader::fullName));
        return List.copyOf(shares.subList(0, Math.min(3, shares.size())));
    }

    static <T> List<T> dropTrailingGaps(List<T> points, Function<T, Long> views) {
        int end = points.size();
        while (end > 0 && views.apply(points.get(end - 1)) == null) {
            end--;
        }
        return end == points.size() ? points : new ArrayList<>(points.subList(0, end));
    }

    private Map<Long, TrafficNotice> trafficNotices(DashboardPeriod period, List<Repository> tracked) {
        LocalDate today = LocalDate.now(clock);
        boolean windowOpen = planningWindow.isOpen();
        Map<Long, Set<LocalDate>> stored = new HashMap<>();
        for (TrafficJdbcRepository.StoredTrafficDay day : trafficJdbcRepository.trackedTrafficDays(period.from(), period.to())) {
            stored.computeIfAbsent(day.repositoryId(), id -> new HashSet<>()).add(day.date());
        }
        Map<Long, LocalDate> latest = new HashMap<>();
        for (TrafficJdbcRepository.LatestTrafficDate day : trafficJdbcRepository.latestTrackedTrafficDates()) {
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
            TrafficCompleteness.Notice notice = TrafficCompleteness.portfolio(
                    today,
                    windowOpen,
                    period.from(),
                    period.to(),
                    stored.getOrDefault(repository.id(), Set.of()),
                    latest.get(repository.id()),
                    TrafficCompleteness.jobState(trafficJobs.get(repository.id()))
            );
            if (notice == null) {
                continue;
            }
            notices.put(repository.id(), new TrafficNotice(
                    notice.reason().name(),
                    notice.date(),
                    notice.lastTrafficDate()
            ));
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
        boolean yesterdayStored = trafficJdbcRepository.latestDate(repositoryId)
                .filter(date -> !date.isBefore(today.minusDays(1)))
                .isPresent();
        TrafficCompleteness.Notice notice = TrafficCompleteness.status(
                today,
                planningWindow.isOpen(),
                yesterdayStored,
                TrafficCompleteness.jobState(trafficJob)
        );
        if (notice == null) {
            return null;
        }
        return new TrafficNotice(notice.reason().name(), notice.date(), notice.lastTrafficDate());
    }

    private static CollectionWarning collectionWarning(int partialCount) {
        if (partialCount <= 0) {
            return null;
        }
        String message = partialCount == 1
                ? "1 repository has partial collection"
                : partialCount + " repositories have partial collection";
        return new CollectionWarning(partialCount, message);
    }

    static JobStatus jobStatus(CollectionJob job) {
        return new JobStatus(job.jobType().name(), job.status().name(), job.errorMessage());
    }

    private static ActiveCollection activeCollection(List<CollectionRun> latestRuns) {
        CollectionRun active = latestRuns.stream()
                .filter(run -> run.status() == CollectionRunStatus.RUNNING || run.status() == CollectionRunStatus.PLANNED)
                .findFirst()
                .orElse(null);
        if (active == null) {
            return null;
        }
        return new ActiveCollection(active.status().name(), active.successfulJobs(), active.plannedJobs());
    }

    private static MetricValue metric(long current, long previous, DashboardPeriod period) {
        Double growth = period.allTime() ? null : GrowthMath.percent(current, previous);
        return new MetricValue(current, growth);
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
            List<RepositoryMetrics> table
    ) {
    }

    public record RepositoryMetrics(
            long id,
            String fullName,
            long visitors,
            long views,
            long clones,
            int stars
    ) {
    }

    public record RepositoryStatsHistory(
            long repositoryId,
            String period,
            StatsTotals totals,
            List<StatsHistoryPoint> points
    ) {
    }

    public record StatsTotals(
            StatsMetricTotal stars,
            StatsMetricTotal forks,
            StatsMetricTotal watchers,
            StatsMetricTotal contributors
    ) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record StatsMetricTotal(Integer value, Integer absoluteChange, Double growthPercent) {
    }

    public record StatsHistoryPoint(
            LocalDate date,
            int stars,
            int forks,
            int watchers,
            int contributors
    ) {
    }

    public record SnapshotHistoryResponse(
            long repositoryId,
            String kind,
            int days,
            LocalDate from,
            LocalDate to,
            List<LocalDate> dates,
            List<SnapshotHistoryRow> rows
    ) {
    }

    public record SnapshotHistoryRow(
            String key,
            String title,
            List<SnapshotHistoryCell> cells
    ) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SnapshotHistoryCell(
            LocalDate date,
            Integer visitors,
            Integer views,
            Integer visitorsDelta,
            Integer viewsDelta,
            boolean firstSeen
    ) {
    }

    public record RepositoryTrafficTotals(
            MetricValue views,
            MetricValue uniqueVisitors,
            MetricValue clones,
            MetricValue uniqueCloners
    ) {
    }

    public record RepositoryTrafficSnapshot(
            Repository repository,
            GitHubOwner owner,
            String period,
            RepositoryTrafficTotals totals,
            List<TrafficChartPoint> traffic,
            List<TrafficJdbcRepository.ReferrerRow> referrers,
            Instant referrerSnapshotAt,
            List<TrafficJdbcRepository.PathRow> paths,
            Instant pathSnapshotAt,
            CollectionController.CollectionRunResponse lastCollection,
            TrafficNotice trafficGap
    ) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record TrafficChartPoint(
            LocalDate date,
            Long views,
            Long uniqueVisitors,
            Long clones,
            Long uniqueCloners
    ) {
    }
}
