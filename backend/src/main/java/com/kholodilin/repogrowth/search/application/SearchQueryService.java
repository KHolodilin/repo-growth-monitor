package com.kholodilin.repogrowth.search.application;

import com.kholodilin.repogrowth.collection.planner.PlanningWindow;
import com.kholodilin.repogrowth.common.api.ApiException;
import com.kholodilin.repogrowth.common.config.SearchProperties;
import com.kholodilin.repogrowth.repository.application.RepositoryService;
import com.kholodilin.repogrowth.search.domain.SearchQuery;
import com.kholodilin.repogrowth.search.domain.SearchResult;
import com.kholodilin.repogrowth.search.domain.SearchRun;
import com.kholodilin.repogrowth.search.planner.SearchPlanner;
import com.kholodilin.repogrowth.search.persistence.SearchQueryJdbcRepository;
import com.kholodilin.repogrowth.search.persistence.SearchResultJdbcRepository;
import com.kholodilin.repogrowth.search.persistence.SearchRunJdbcRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class SearchQueryService {

    private final SearchQueryJdbcRepository queryRepository;
    private final SearchRunJdbcRepository runRepository;
    private final SearchResultJdbcRepository resultRepository;
    private final SearchPlanner searchPlanner;
    private final PlanningWindow planningWindow;
    private final RepositoryService repositoryService;
    private final SearchProperties searchProperties;

    public SearchQueryService(
            SearchQueryJdbcRepository queryRepository,
            SearchRunJdbcRepository runRepository,
            SearchResultJdbcRepository resultRepository,
            SearchPlanner searchPlanner,
            PlanningWindow planningWindow,
            RepositoryService repositoryService,
            SearchProperties searchProperties
    ) {
        this.queryRepository = queryRepository;
        this.runRepository = runRepository;
        this.resultRepository = resultRepository;
        this.searchPlanner = searchPlanner;
        this.planningWindow = planningWindow;
        this.repositoryService = repositoryService;
        this.searchProperties = searchProperties;
    }

    public List<SearchQuery> list(long repositoryId) {
        repositoryService.get(repositoryId);
        return queryRepository.findByRepository(repositoryId);
    }

    public SearchQuery create(long repositoryId, String name, String query, Boolean enabled, Integer resultLimit) {
        repositoryService.get(repositoryId);
        if (query == null || query.isBlank()) {
            throw ApiException.validation("Search query must not be blank");
        }
        int limit = resultLimit == null ? searchProperties.defaultResultLimit() : resultLimit;
        boolean on = enabled == null || enabled;
        return queryRepository.insert(repositoryId, name == null || name.isBlank() ? query : name, query, on, limit);
    }

    public SearchQuery update(long id, String name, String query, Boolean enabled, Integer resultLimit) {
        SearchQuery existing = getQuery(id);
        return queryRepository.update(
                id,
                name == null ? existing.name() : name,
                query == null ? existing.query() : query,
                enabled == null ? existing.enabled() : enabled,
                resultLimit == null ? existing.resultLimit() : resultLimit
        );
    }

    public void delete(long id) {
        getQuery(id);
        queryRepository.delete(id);
    }

    public SearchQuery getQuery(long id) {
        return queryRepository.findById(id).orElseThrow(() -> ApiException.notFound("Search query not found"));
    }

    public long runNow(long searchQueryId) {
        SearchQuery query = getQuery(searchQueryId);
        return searchPlanner.planQuery(query.id(), query.repositoryId(), planningWindow.businessDate());
    }

    public SearchHistory history(long searchQueryId) {
        SearchQuery query = getQuery(searchQueryId);
        List<SearchRun> runs = runRepository.history(searchQueryId).stream()
                .filter(run -> run.status().name().equals("SUCCESS"))
                .sorted(Comparator.comparing(SearchRun::businessDate))
                .toList();
        Integer current = latestPosition(runs);
        Integer change7 = changeSince(runs, 7);
        Integer change30 = changeSince(runs, 30);
        Integer best = runs.stream()
                .map(SearchRun::trackedRepositoryPosition)
                .filter(Objects::nonNull)
                .min(Integer::compareTo)
                .orElse(null);
        List<RankPoint> points = runs.stream()
                .map(run -> new RankPoint(run.businessDate(), run.trackedRepositoryPosition(), run.id()))
                .toList();
        return new SearchHistory(query, current, change7, change30, best, points);
    }

    public SearchRunResults results(long searchRunId) {
        SearchRun run = runRepository.findById(searchRunId)
                .orElseThrow(() -> ApiException.notFound("Search run not found"));
        SearchQuery query = getQuery(run.searchQueryId());
        List<SearchResult> current = resultRepository.findByRun(searchRunId);
        Map<Long, Integer> previousPositions = runRepository.previousSuccessful(run.searchQueryId(), run.businessDate())
                .map(previous -> resultRepository.findByRun(previous.id()).stream()
                        .collect(Collectors.toMap(SearchResult::githubRepositoryId, SearchResult::position, (a, b) -> a)))
                .orElse(Map.of());
        List<SearchResultRow> rows = new ArrayList<>();
        for (SearchResult result : current) {
            Integer previous = previousPositions.get(result.githubRepositoryId());
            Integer delta = previous == null ? null : previous - result.position();
            rows.add(new SearchResultRow(result, delta));
        }
        return new SearchRunResults(run, query, rows);
    }

    public List<SearchHistory> visibility(long repositoryId) {
        return list(repositoryId).stream().map(query -> history(query.id())).toList();
    }

    private Integer latestPosition(List<SearchRun> runs) {
        if (runs.isEmpty()) {
            return null;
        }
        return runs.get(runs.size() - 1).trackedRepositoryPosition();
    }

    private Integer changeSince(List<SearchRun> runs, int days) {
        if (runs.isEmpty()) {
            return null;
        }
        SearchRun latest = runs.get(runs.size() - 1);
        LocalDate target = latest.businessDate().minusDays(days);
        SearchRun baseline = null;
        for (SearchRun run : runs) {
            if (!run.businessDate().isAfter(target)) {
                baseline = run;
            }
        }
        if (baseline == null || baseline.trackedRepositoryPosition() == null || latest.trackedRepositoryPosition() == null) {
            return null;
        }
        return baseline.trackedRepositoryPosition() - latest.trackedRepositoryPosition();
    }

    public record RankPoint(LocalDate date, Integer position, long searchRunId) {
    }

    public record SearchHistory(
            SearchQuery query,
            Integer currentRank,
            Integer change7d,
            Integer change30d,
            Integer bestRank,
            List<RankPoint> points
    ) {
    }

    public record SearchResultRow(SearchResult result, Integer positionDelta) {
    }

    public record SearchRunResults(SearchRun run, SearchQuery query, List<SearchResultRow> rows) {
    }
}
