package com.kholodilin.repogrowth.search.service;

import com.kholodilin.repogrowth.catalog.service.RepositoryService;
import com.kholodilin.repogrowth.common.api.ApiException;
import com.kholodilin.repogrowth.common.config.CollectorSearchProperties;
import com.kholodilin.repogrowth.common.config.SearchProperties;
import com.kholodilin.repogrowth.search.model.SearchQuery;
import com.kholodilin.repogrowth.search.model.SearchResult;
import com.kholodilin.repogrowth.search.model.SearchRun;
import com.kholodilin.repogrowth.search.repository.SearchQueryJdbcRepository;
import com.kholodilin.repogrowth.search.repository.SearchResultJdbcRepository;
import com.kholodilin.repogrowth.search.repository.SearchRunJdbcRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class SearchQueryService {

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private com.kholodilin.repogrowth.collection.service.CollectionRequestService commands;

  private final SearchQueryJdbcRepository queryRepository;
  private final SearchRunJdbcRepository runRepository;
  private final SearchResultJdbcRepository resultRepository;
  private final RepositoryService repositoryService;
  private final SearchProperties searchProperties;
  private final CollectorSearchProperties rankSort;

  public SearchQueryService(
      SearchQueryJdbcRepository queryRepository,
      SearchRunJdbcRepository runRepository,
      SearchResultJdbcRepository resultRepository,
      RepositoryService repositoryService,
      SearchProperties searchProperties,
      CollectorSearchProperties rankSort) {
    this.queryRepository = queryRepository;
    this.runRepository = runRepository;
    this.resultRepository = resultRepository;
    this.repositoryService = repositoryService;
    this.searchProperties = searchProperties;
    this.rankSort = rankSort == null ? new CollectorSearchProperties(null, null) : rankSort;
  }

  public List<SearchQuery> list(long repositoryId) {
    repositoryService.get(repositoryId);
    return queryRepository.findByRepository(repositoryId);
  }

  public SearchQuery create(
      long repositoryId, String name, String query, Boolean enabled, Integer resultLimit) {
    repositoryService.get(repositoryId);
    String normalized = normalizeQuery(query);
    ensureUnique(repositoryId, normalized, null);
    int limit = resultLimit == null ? searchProperties.defaultResultLimit() : resultLimit;
    boolean on = enabled == null || enabled;
    String label = name == null || name.isBlank() ? normalized : name.trim();
    return queryRepository.insert(repositoryId, label, normalized, on, limit);
  }

  public SearchQuery update(
      long id, String name, String query, Boolean enabled, Integer resultLimit) {
    SearchQuery existing = getQuery(id);
    String nextQuery = query == null ? existing.query() : normalizeQuery(query);
    ensureUnique(existing.repositoryId(), nextQuery, existing.id());
    return queryRepository.update(
        id,
        name == null ? existing.name() : name,
        nextQuery,
        enabled == null ? existing.enabled() : enabled,
        resultLimit == null ? existing.resultLimit() : resultLimit);
  }

  private void ensureUnique(long repositoryId, String normalizedQuery, Long excludeId) {
    queryRepository
        .findNormalized(repositoryId, normalizedQuery.toLowerCase(), excludeId)
        .ifPresent(ignored -> {
          throw ApiException.validation("This search query is already tracked for the repository");
        });
  }

  static String normalizeQuery(String query) {
    if (query == null || query.isBlank()) {
      throw ApiException.validation("Search query must not be blank");
    }
    return query.trim().replaceAll("\\s+", " ");
  }

  @org.springframework.transaction.annotation.Transactional
  public void delete(long id) {
    getQuery(id);
    if (commands != null) commands.cancelSearch(id);
    queryRepository.delete(id);
  }

  public SearchQuery getQuery(long id) {
    return queryRepository
        .findById(id)
        .orElseThrow(() -> ApiException.notFound("Search query not found"));
  }

  /** Search runs are collected only by the Collector; the product never queues local jobs. */
  public long runNow(long searchQueryId) {
    throw ApiException.serviceUnavailable("Collector orchestration is required");
  }

  /** Search runs are collected only by the Collector; the product never queues local jobs. */
  public List<Long> runAll(long repositoryId) {
    throw ApiException.serviceUnavailable("Collector orchestration is required");
  }

  public SearchHistory history(long searchQueryId) {
    SearchQuery query = getQuery(searchQueryId);
    List<SearchRun> runs = runRepository.snapshots(searchQueryId);
    SearchRun latestSnapshot = runs.isEmpty() ? null : runs.get(runs.size() - 1);
    String sortMode = chartSortMode(runs, latestSnapshot, rankSort.sortModeLabel());
    // Deltas and best only within the same GitHub sort mode (Best Match vs Stars are not
    // comparable).
    List<SearchRun> comparable =
        runs.stream().filter(run -> sameSortMode(run.sortMode(), sortMode)).toList();
    Integer current = latestPosition(comparable);
    boolean hasPrevious = comparable.size() >= 2;
    Integer previous =
        hasPrevious ? comparable.get(comparable.size() - 2).trackedRepositoryPosition() : null;
    RankChangeMath.Change change =
        RankChangeMath.between(hasPrevious, previous, current, query.resultLimit());
    Integer change7 = changeSince(comparable, 7);
    Integer change30 = changeSince(comparable, 30);
    Integer best =
        comparable.stream()
            .map(SearchRun::trackedRepositoryPosition)
            .filter(Objects::nonNull)
            .min(Integer::compareTo)
            .orElse(null);
    // Chart must use the same sort mode as Best/Current Rank — Best Match vs Stars are not
    // comparable.
    List<RankPoint> points =
        comparable.stream()
            .map(
                run -> new RankPoint(run.businessDate(), run.trackedRepositoryPosition(), run.id()))
            .toList();
    SearchRun latestRun = runRepository.latest(searchQueryId).orElse(null);
    SearchRun latestComparable =
        comparable.isEmpty() ? null : comparable.get(comparable.size() - 1);
    // Date the row by the snapshot that backs Current Rank, not an incompatible Best Match tip.
    Instant lastChecked = latestComparable == null ? null : latestComparable.snapshotAt();
    String searchStatus = latestRun == null ? null : latestRun.status().name();
    String enrichmentStatus = latestRun == null ? null : latestRun.enrichmentStatus();
    Integer totalResults = latestComparable == null ? null : latestComparable.totalCount();
    return new SearchHistory(
        query,
        current,
        change,
        change7,
        change30,
        best,
        normalizeSortMode(sortMode),
        points,
        lastChecked,
        searchStatus,
        enrichmentStatus,
        totalResults,
        chartMissedDates(searchQueryId, sortMode, runs));
  }

  public SearchRunResults latestResults(long searchQueryId) {
    getQuery(searchQueryId);
    SearchRun run =
        runRepository
            .latestSnapshot(searchQueryId)
            .or(() -> runRepository.latest(searchQueryId))
            .orElseThrow(() -> ApiException.notFound("Search results not found"));
    return results(run.id());
  }

  public SearchRunResults results(long searchRunId) {
    SearchRun run =
        runRepository
            .findById(searchRunId)
            .orElseThrow(() -> ApiException.notFound("Search run not found"));
    SearchQuery query = getQuery(run.searchQueryId());
    List<SearchResult> current = resultRepository.findByRun(searchRunId);
    Map<Long, Integer> previousPositions =
        runRepository
            .previousSnapshot(run.searchQueryId(), run.businessDate(), run.sortMode())
            .map(previous ->
                resultRepository.findByRun(previous.id()).stream()
                    .collect(
                        Collectors.toMap(
                            SearchResult::githubRepositoryId, SearchResult::position, (a, b) -> a)))
            .orElse(Map.of());
    List<SearchResultRow> rows = new ArrayList<>();
    for (SearchResult result : current) {
      Integer previous = previousPositions.get(result.githubRepositoryId());
      Integer delta = previous == null ? null : previous - result.position();
      rows.add(new SearchResultRow(result, delta));
    }
    return new SearchRunResults(run, query, rows);
  }

  public SearchSeries series(long searchQueryId) {
    getQuery(searchQueryId);
    Map<Long, String> names = new LinkedHashMap<>();
    Map<Long, List<SeriesPoint>> points = new LinkedHashMap<>();
    for (SearchResultJdbcRepository.SnapshotValue row :
        resultRepository.findSnapshots(searchQueryId)) {
      names.put(row.githubRepositoryId(), row.fullName());
      points
          .computeIfAbsent(row.githubRepositoryId(), ignored -> new ArrayList<>())
          .add(new SeriesPoint(row.date(), row.position(), row.stars(), row.forks()));
    }
    List<SeriesLine> lines = new ArrayList<>();
    for (Map.Entry<Long, String> entry : names.entrySet()) {
      lines.add(new SeriesLine(entry.getKey(), entry.getValue(), points.get(entry.getKey())));
    }
    return new SearchSeries(lines);
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

  private static boolean sameSortMode(String left, String right) {
    return normalizeSortMode(left).equals(normalizeSortMode(right));
  }

  /** Blank/null uses the configured preferred mode ({@code collector.search.rank-sort}). */
  static String normalizeSortMode(String sortMode) {
    return normalizeSortMode(sortMode, "best_match");
  }

  static String normalizeSortMode(String sortMode, String preferred) {
    return sortMode == null || sortMode.isBlank() ? preferred : sortMode;
  }

  static String chartSortMode(List<SearchRun> runs, SearchRun latestSnapshot) {
    return chartSortMode(runs, latestSnapshot, "best_match");
  }

  static String chartSortMode(List<SearchRun> runs, SearchRun latestSnapshot, String preferred) {
    if (latestSnapshot == null) {
      return preferred;
    }
    String latest = normalizeSortMode(latestSnapshot.sortMode(), preferred);
    if (preferred.equals(latest)) {
      return preferred;
    }
    boolean hasPreferred =
        runs.stream()
            .anyMatch(run -> preferred.equals(normalizeSortMode(run.sortMode(), preferred)));
    return hasPreferred ? preferred : latest;
  }

  /**
   * Planner MISSED days plus tip days that only have an incompatible GitHub sort mode. Preferring
   * stars must not leave a silent empty tip after Best Match overwrote the series.
   */
  private List<LocalDate> chartMissedDates(
      long searchQueryId, String sortMode, List<SearchRun> runs) {
    LinkedHashSet<LocalDate> missed = new LinkedHashSet<>(runRepository.missedDates(searchQueryId));
    Set<LocalDate> comparableDates =
        runs.stream()
            .filter(run -> sameSortMode(run.sortMode(), sortMode))
            .map(SearchRun::businessDate)
            .collect(Collectors.toSet());
    for (SearchRun run : runs) {
      if (!comparableDates.contains(run.businessDate())) {
        missed.add(run.businessDate());
      }
    }
    return List.copyOf(missed);
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
    if (baseline == null
        || baseline.trackedRepositoryPosition() == null
        || latest.trackedRepositoryPosition() == null) {
      return null;
    }
    return baseline.trackedRepositoryPosition() - latest.trackedRepositoryPosition();
  }

  public record RankPoint(LocalDate date, Integer position, long searchRunId) {}

  public record SearchHistory(
      SearchQuery query,
      Integer currentRank,
      RankChangeMath.Change change,
      Integer change7d,
      Integer change30d,
      Integer bestRank,
      String sortMode,
      List<RankPoint> points,
      Instant lastChecked,
      String searchStatus,
      String enrichmentStatus,
      Integer totalResults,
      List<LocalDate> missedDates) {}

  public record SearchResultRow(SearchResult result, Integer positionDelta) {}

  public record SearchRunResults(SearchRun run, SearchQuery query, List<SearchResultRow> rows) {}

  public record SeriesPoint(LocalDate date, int position, int stars, int forks) {}

  public record SeriesLine(long githubRepositoryId, String fullName, List<SeriesPoint> points) {}

  public record SearchSeries(List<SeriesLine> series) {}
}
