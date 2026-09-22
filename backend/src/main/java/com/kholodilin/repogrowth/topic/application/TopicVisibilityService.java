package com.kholodilin.repogrowth.topic.application;

import com.kholodilin.repogrowth.collection.planner.PlanningWindow;
import com.kholodilin.repogrowth.common.api.ApiException;
import com.kholodilin.repogrowth.repository.application.RepositoryService;
import com.kholodilin.repogrowth.search.application.RankChangeMath;
import com.kholodilin.repogrowth.topic.domain.TopicResult;
import com.kholodilin.repogrowth.topic.domain.TopicRun;
import com.kholodilin.repogrowth.topic.domain.TopicWatch;
import com.kholodilin.repogrowth.topic.planner.TopicPlanner;
import com.kholodilin.repogrowth.topic.persistence.TopicResultJdbcRepository;
import com.kholodilin.repogrowth.topic.persistence.TopicRunJdbcRepository;
import com.kholodilin.repogrowth.topic.persistence.TopicWatchJdbcRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class TopicVisibilityService {

    private final TopicWatchJdbcRepository watchRepository;
    private final TopicRunJdbcRepository runRepository;
    private final TopicResultJdbcRepository resultRepository;
    private final TopicWatchSync topicWatchSync;
    private final TopicPlanner topicPlanner;
    private final PlanningWindow planningWindow;
    private final RepositoryService repositoryService;

    public TopicVisibilityService(
            TopicWatchJdbcRepository watchRepository,
            TopicRunJdbcRepository runRepository,
            TopicResultJdbcRepository resultRepository,
            TopicWatchSync topicWatchSync,
            TopicPlanner topicPlanner,
            PlanningWindow planningWindow,
            RepositoryService repositoryService
    ) {
        this.watchRepository = watchRepository;
        this.runRepository = runRepository;
        this.resultRepository = resultRepository;
        this.topicWatchSync = topicWatchSync;
        this.topicPlanner = topicPlanner;
        this.planningWindow = planningWindow;
        this.repositoryService = repositoryService;
    }

    public List<TopicHistory> visibility(long repositoryId, String scope) {
        repositoryService.get(repositoryId);
        boolean allLanguages = parseAllLanguages(scope);
        topicWatchSync.sync(repositoryId);
        return watchRepository.findEnabledByRepository(repositoryId, allLanguages).stream()
                .map(watch -> history(watch.id()))
                .toList();
    }

    public TopicHistory history(long topicWatchId) {
        TopicWatch watch = getWatch(topicWatchId);
        List<TopicRun> runs = runRepository.snapshots(topicWatchId);
        Integer current = latestPosition(runs);
        boolean hasPrevious = runs.size() >= 2;
        Integer previous = hasPrevious ? runs.get(runs.size() - 2).trackedRepositoryPosition() : null;
        RankChangeMath.Change change = RankChangeMath.between(hasPrevious, previous, current, watch.resultLimit());
        Integer change7 = changeSince(runs, 7);
        Integer change30 = changeSince(runs, 30);
        Integer best = runs.stream()
                .map(TopicRun::trackedRepositoryPosition)
                .filter(Objects::nonNull)
                .min(Integer::compareTo)
                .orElse(null);
        List<RankPoint> points = runs.stream()
                .map(run -> new RankPoint(run.businessDate(), run.trackedRepositoryPosition(), run.id()))
                .toList();
        TopicRun latestRun = runRepository.latest(topicWatchId).orElse(null);
        Instant lastChecked = runs.isEmpty() ? null : runs.get(runs.size() - 1).snapshotAt();
        String searchStatus = latestRun == null ? null : latestRun.status().name();
        Integer totalResults = runs.isEmpty() ? null : runs.get(runs.size() - 1).totalCount();
        return new TopicHistory(
                watch,
                current,
                change,
                change7,
                change30,
                best,
                points,
                lastChecked,
                searchStatus,
                totalResults,
                runRepository.missedDates(topicWatchId)
        );
    }

    public TopicHistory historyByTopic(long repositoryId, String topic, String scope) {
        repositoryService.get(repositoryId);
        boolean allLanguages = parseAllLanguages(scope);
        topicWatchSync.sync(repositoryId);
        TopicWatch watch = watchRepository.findEnabledByRepository(repositoryId, allLanguages).stream()
                .filter(item -> item.topic().equalsIgnoreCase(topic))
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("Topic watch not found"));
        return history(watch.id());
    }

    public long runNow(long topicWatchId) {
        TopicWatch watch = getWatch(topicWatchId);
        return topicPlanner.planWatch(watch.id(), watch.repositoryId(), planningWindow.businessDate());
    }

    public List<Long> runAll(long repositoryId, String scope) {
        repositoryService.get(repositoryId);
        boolean allLanguages = parseAllLanguages(scope);
        topicWatchSync.sync(repositoryId);
        LocalDate date = planningWindow.businessDate();
        return watchRepository.findEnabledByRepository(repositoryId, allLanguages).stream()
                .map(watch -> topicPlanner.planWatch(watch.id(), watch.repositoryId(), date))
                .toList();
    }

    public TopicRunResults latestResults(long topicWatchId) {
        getWatch(topicWatchId);
        TopicRun run = runRepository.latestSnapshot(topicWatchId)
                .or(() -> runRepository.latest(topicWatchId))
                .orElseThrow(() -> ApiException.notFound("Topic results not found"));
        return results(run.id());
    }

    public TopicRunResults latestResultsByTopic(long repositoryId, String topic, String scope) {
        TopicHistory history = historyByTopic(repositoryId, topic, scope);
        return latestResults(history.watch().id());
    }

    public TopicRunResults results(long topicRunId) {
        TopicRun run = runRepository.findById(topicRunId)
                .orElseThrow(() -> ApiException.notFound("Topic run not found"));
        TopicWatch watch = getWatch(run.topicWatchId());
        List<TopicResult> current = resultRepository.findByRun(topicRunId);
        Map<Long, Integer> previousPositions = runRepository.previousSnapshot(run.topicWatchId(), run.businessDate())
                .map(previous -> resultRepository.findByRun(previous.id()).stream()
                        .collect(Collectors.toMap(TopicResult::githubRepositoryId, TopicResult::position, (a, b) -> a)))
                .orElse(Map.of());
        List<TopicResultRow> rows = new ArrayList<>();
        for (TopicResult result : current) {
            Integer previous = previousPositions.get(result.githubRepositoryId());
            Integer delta = previous == null ? null : previous - result.position();
            rows.add(new TopicResultRow(result, delta));
        }
        return new TopicRunResults(run, watch, rows);
    }

    public TopicWatch getWatch(long id) {
        return watchRepository.findById(id).orElseThrow(() -> ApiException.notFound("Topic watch not found"));
    }

    static boolean parseAllLanguages(String scope) {
        if (scope == null || scope.isBlank() || "all".equalsIgnoreCase(scope)) {
            return true;
        }
        if ("language".equalsIgnoreCase(scope)) {
            return false;
        }
        throw ApiException.validation("Topic scope must be all or language");
    }

    private Integer latestPosition(List<TopicRun> runs) {
        if (runs.isEmpty()) {
            return null;
        }
        return runs.get(runs.size() - 1).trackedRepositoryPosition();
    }

    private Integer changeSince(List<TopicRun> runs, int days) {
        if (runs.isEmpty()) {
            return null;
        }
        TopicRun latest = runs.get(runs.size() - 1);
        LocalDate target = latest.businessDate().minusDays(days);
        TopicRun baseline = null;
        for (TopicRun run : runs) {
            if (!run.businessDate().isAfter(target)) {
                baseline = run;
            }
        }
        if (baseline == null || baseline.trackedRepositoryPosition() == null || latest.trackedRepositoryPosition() == null) {
            return null;
        }
        return baseline.trackedRepositoryPosition() - latest.trackedRepositoryPosition();
    }

    public record RankPoint(LocalDate date, Integer position, long topicRunId) {
    }

    public record TopicHistory(
            TopicWatch watch,
            Integer currentRank,
            RankChangeMath.Change change,
            Integer change7d,
            Integer change30d,
            Integer bestRank,
            List<RankPoint> points,
            Instant lastChecked,
            String searchStatus,
            Integer totalResults,
            List<LocalDate> missedDates
    ) {
    }

    public record TopicResultRow(TopicResult result, Integer positionDelta) {
    }

    public record TopicRunResults(TopicRun run, TopicWatch watch, List<TopicResultRow> rows) {
    }
}
