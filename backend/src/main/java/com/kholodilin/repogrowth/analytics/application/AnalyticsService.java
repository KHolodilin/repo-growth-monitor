package com.kholodilin.repogrowth.analytics.application;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.kholodilin.repogrowth.analytics.api.DashboardResponse;
import com.kholodilin.repogrowth.analytics.api.DashboardResponse.ActiveCollection;
import com.kholodilin.repogrowth.analytics.api.DashboardResponse.CollectionWarning;
import com.kholodilin.repogrowth.analytics.api.DashboardResponse.DashboardState;
import com.kholodilin.repogrowth.analytics.api.DashboardResponse.DashboardSummary;
import com.kholodilin.repogrowth.analytics.api.DashboardResponse.JobStatus;
import com.kholodilin.repogrowth.analytics.api.DashboardResponse.MetricValue;
import com.kholodilin.repogrowth.analytics.api.DashboardResponse.PartialData;
import com.kholodilin.repogrowth.analytics.api.DashboardResponse.RepositoryRow;
import com.kholodilin.repogrowth.analytics.api.DashboardResponse.StarsMetric;
import com.kholodilin.repogrowth.analytics.api.DashboardResponse.TrafficPoint;
import com.kholodilin.repogrowth.collection.api.CollectionController;
import com.kholodilin.repogrowth.collection.domain.CollectionJob;
import com.kholodilin.repogrowth.collection.domain.CollectionRun;
import com.kholodilin.repogrowth.collection.domain.CollectionRunStatus;
import com.kholodilin.repogrowth.collection.persistence.CollectionJobJdbcRepository;
import com.kholodilin.repogrowth.collection.persistence.CollectionRunJdbcRepository;
import com.kholodilin.repogrowth.repository.application.RepositoryService;
import com.kholodilin.repogrowth.search.application.ActivityClassifier;
import com.kholodilin.repogrowth.search.domain.ActivityStatus;
import com.kholodilin.repogrowth.repository.domain.GitHubOwner;
import com.kholodilin.repogrowth.repository.domain.Repository;
import com.kholodilin.repogrowth.traffic.domain.TrafficDaily;
import com.kholodilin.repogrowth.traffic.persistence.TrafficJdbcRepository;
import com.kholodilin.repogrowth.traffic.persistence.TrafficJdbcRepository.RepositoryPeriodTotals;
import com.kholodilin.repogrowth.traffic.persistence.TrafficJdbcRepository.TrafficTotals;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class AnalyticsService {

    private static final DateTimeFormatter GAP_DATE = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH);

    private final RepositoryService repositoryService;
    private final TrafficJdbcRepository trafficJdbcRepository;
    private final CollectionRunJdbcRepository runRepository;
    private final CollectionJobJdbcRepository jobRepository;
    private final CollectionController collectionController;
    private final ActivityClassifier activityClassifier;
    private final Clock clock;

    public AnalyticsService(
            RepositoryService repositoryService,
            TrafficJdbcRepository trafficJdbcRepository,
            CollectionRunJdbcRepository runRepository,
            CollectionJobJdbcRepository jobRepository,
            CollectionController collectionController,
            ActivityClassifier activityClassifier,
            Clock clock
    ) {
        this.repositoryService = repositoryService;
        this.trafficJdbcRepository = trafficJdbcRepository;
        this.runRepository = runRepository;
        this.jobRepository = jobRepository;
        this.collectionController = collectionController;
        this.activityClassifier = activityClassifier;
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

    public RepositoryTrafficSnapshot traffic(long repositoryId, String periodParam) {
        Repository repository = repositoryService.get(repositoryId);
        GitHubOwner owner = repositoryService.owner(repository.ownerId());
        LocalDate today = LocalDate.now(clock);
        String normalized = DashboardPeriod.normalize(periodParam);
        LocalDate earliest = null;
        if ("all".equals(normalized)) {
            earliest = trafficJdbcRepository.history(repositoryId, null).stream()
                    .map(TrafficDaily::trafficDate)
                    .min(LocalDate::compareTo)
                    .orElse(today);
        }
        DashboardPeriod period = DashboardPeriod.of(periodParam, today, earliest);
        List<TrafficDaily> history = trafficJdbcRepository.history(repositoryId, period.from());
        TrafficTotals totals = trafficJdbcRepository.totals(repositoryId, period.allTime() ? null : period.from());
        CollectionRun latestRun = runRepository.latestForRepository(repositoryId).orElse(null);
        return new RepositoryTrafficSnapshot(
                repository,
                owner,
                period.value(),
                totals,
                repositorySeries(period, history),
                trafficJdbcRepository.referrersInRange(repositoryId, period.from(), period.to(), clock.getZone()),
                trafficJdbcRepository.pathsInRange(repositoryId, period.from(), period.to(), clock.getZone()),
                latestRun == null ? null : collectionController.toResponse(latestRun)
        );
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
        List<TrafficPoint> traffic = series(period);
        Map<Long, Long> previousVisitors = previousVisitorsByRepo(period);
        List<CollectionRun> latestRuns = runRepository.latestPerTrackedRepository();
        Map<Long, CollectionRun> runByRepo = latestRuns.stream()
                .collect(Collectors.toMap(CollectionRun::repositoryId, run -> run, (left, right) -> left));
        Map<Long, List<CollectionJob>> jobsByRun = jobRepository.findByRunIds(
                        latestRuns.stream().map(CollectionRun::id).toList()
                ).stream()
                .collect(Collectors.groupingBy(CollectionJob::collectionRunId));
        List<RepositoryRow> rows = trafficJdbcRepository.totalsByTrackedRepository(period.from(), period.to())
                .stream()
                .map(row -> toRow(row, period, previousVisitors, runByRepo, jobsByRun))
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
                partialData(traffic),
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
                rows
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
        List<RepositoryRow> rows = tracked.stream()
                .map(repository -> {
                    CollectionRun run = runByRepo.get(repository.id());
                    List<JobStatus> jobs = run == null
                            ? List.of()
                            : jobsByRun.getOrDefault(run.id(), List.of()).stream()
                                    .map(job -> new JobStatus(job.jobType().name(), job.status().name()))
                                    .toList();
                    Instant activityAt = repository.activityAt();
                    return new RepositoryRow(
                            repository.id(),
                            repository.fullName(),
                            0,
                            0,
                            0,
                            repository.stars(),
                            null,
                            run == null ? null : run.status().name(),
                            jobs,
                            repository.archived(),
                            activityAt,
                            activityClassifier.classify(repository.archived(), activityAt).name()
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
                rows
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
                List.of()
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
            Map<Long, List<CollectionJob>> jobsByRun
    ) {
        Double growth = period.allTime()
                ? null
                : GrowthMath.percent(row.uniqueVisitors(), previousVisitors.getOrDefault(row.repositoryId(), 0L));
        CollectionRun run = runByRepo.get(row.repositoryId());
        List<JobStatus> jobs = run == null
                ? List.of()
                : jobsByRun.getOrDefault(run.id(), List.of()).stream()
                        .map(job -> new JobStatus(job.jobType().name(), job.status().name()))
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
                growth,
                run == null ? null : run.status().name(),
                jobs,
                row.archived(),
                activityAt,
                activityStatus.name()
        );
    }

    private List<TrafficPoint> series(DashboardPeriod period) {
        return trafficJdbcRepository.dailyPortfolio(period.from(), period.to()).stream()
                .map(day -> new TrafficPoint(day.date(), day.views(), day.uniqueVisitors(), day.clones()))
                .toList();
    }

    static <T> List<T> dropTrailingGaps(List<T> points, Function<T, Long> views) {
        int end = points.size();
        while (end > 0 && views.apply(points.get(end - 1)) == null) {
            end--;
        }
        return end == points.size() ? points : new ArrayList<>(points.subList(0, end));
    }

    static PartialData partialData(List<TrafficPoint> traffic) {
        List<LocalDate> observed = traffic.stream()
                .filter(point -> point.views() != null)
                .map(TrafficPoint::date)
                .sorted()
                .toList();
        if (observed.isEmpty()) {
            return null;
        }
        LocalDate first = observed.getFirst();
        LocalDate last = observed.getLast();
        Set<LocalDate> present = new HashSet<>(observed);
        List<LocalDate> gaps = new ArrayList<>();
        for (LocalDate day = first; !day.isAfter(last); day = day.plusDays(1)) {
            if (!present.contains(day)) {
                gaps.add(day);
            }
        }
        if (gaps.isEmpty()) {
            return null;
        }
        return new PartialData(true, "Data is unavailable for " + formatGapRanges(gaps)
                + " because the service was not running.");
    }

    static String formatGapRanges(List<LocalDate> gaps) {
        List<String> parts = new ArrayList<>();
        LocalDate rangeStart = gaps.getFirst();
        LocalDate rangeEnd = gaps.getFirst();
        for (int i = 1; i < gaps.size(); i++) {
            LocalDate current = gaps.get(i);
            if (current.equals(rangeEnd.plusDays(1))) {
                rangeEnd = current;
                continue;
            }
            parts.add(formatRange(rangeStart, rangeEnd));
            rangeStart = current;
            rangeEnd = current;
        }
        parts.add(formatRange(rangeStart, rangeEnd));
        return String.join(", ", parts);
    }

    private static String formatRange(LocalDate start, LocalDate end) {
        if (start.equals(end)) {
            return GAP_DATE.format(start);
        }
        if (start.getYear() == end.getYear() && start.getMonth() == end.getMonth()) {
            return GAP_DATE.format(start) + "–" + end.getDayOfMonth();
        }
        return GAP_DATE.format(start) + "–" + GAP_DATE.format(end);
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

    public record RepositoryTrafficSnapshot(
            Repository repository,
            GitHubOwner owner,
            String period,
            TrafficJdbcRepository.TrafficTotals totals,
            List<TrafficChartPoint> traffic,
            List<TrafficJdbcRepository.ReferrerRow> referrers,
            List<TrafficJdbcRepository.PathRow> paths,
            CollectionController.CollectionRunResponse lastCollection
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
