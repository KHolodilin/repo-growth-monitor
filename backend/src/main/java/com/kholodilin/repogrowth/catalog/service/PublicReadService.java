package com.kholodilin.repogrowth.catalog.service;

import com.kholodilin.repogrowth.administration.configuration.PublicRoutes;
import com.kholodilin.repogrowth.administration.configuration.PublicationProperties;
import com.kholodilin.repogrowth.administration.service.*;
import com.kholodilin.repogrowth.administration.service.PublicationService;
import com.kholodilin.repogrowth.catalog.service.*;
import com.kholodilin.repogrowth.common.model.period.DashboardPeriod;
import com.kholodilin.repogrowth.common.model.GrowthMath;
import com.kholodilin.repogrowth.search.service.PortfolioHighlightMath;
import com.kholodilin.repogrowth.catalog.repository.PublicReadJdbcRepository;
import com.kholodilin.repogrowth.catalog.repository.RepositoryJdbcRepository;
import com.kholodilin.repogrowth.common.api.ApiException;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * DB-only safe projections. No product identity, planner, token, raw job/error or network
 * dependency.
 */
@Service
@org.springframework.transaction.annotation.Transactional(readOnly = true)
public class PublicReadService {
  @com.fasterxml.jackson.annotation.JsonInclude(
      com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
  public record Page(List<Map<String, Object>> items, String nextCursor, LocalDate nextDate) {
    public Page(List<Map<String, Object>> items, Long nextCursor) {
      this(items, nextCursor == null ? null : nextCursor.toString(), null);
    }
  }

  @com.fasterxml.jackson.annotation.JsonInclude(
      com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
  public record Metric(
      Long value,
      long observedValue,
      long observedRepositories,
      long expectedRepositories,
      boolean complete,
      Long absoluteChange,
      Double growthPercent) {}

  private final PublicReadJdbcRepository reads;
  private final PublicationService policy;
  private final PublicationProperties properties;
  private final ActivityClassifier activityClassifier;
  private final RepositoryHealthService healthService;
  private final RepositoryJdbcRepository repositoryJdbc;

  public PublicReadService(
      PublicReadJdbcRepository reads,
      PublicationService policy,
      PublicationProperties properties,
      ActivityClassifier activityClassifier,
      RepositoryHealthService healthService,
      RepositoryJdbcRepository repositoryJdbc) {
    this.reads = reads;
    this.policy = policy;
    this.properties = properties;
    this.activityClassifier = activityClassifier;
    this.healthService = healthService;
    this.repositoryJdbc = repositoryJdbc;
  }

  private ApiException missing() {
    return ApiException.notFound("Page not found");
  }

  private long ageMillis() {
    return properties.verificationMaxAge().toMillis();
  }

  private int limit(int value) {
    if (value < 1 || value > 100) throw ApiException.validation("limit must be 1..100");
    return value;
  }

  private long cursor(long value) {
    if (value < 0) throw ApiException.validation("Invalid cursor");
    return value;
  }

  public PublicationService.Resolved resolve(String path) {
    return policy.resolve(path);
  }

  public List<Long> scope(String owner, String repository, PublicationService.Module module) {
    var resolved = policy.resolve("/" + owner + (repository == null ? "" : "/" + repository));
    String flag =
        switch (module) {
          case DASHBOARD -> "public_dashboard_enabled";
          case TRAFFIC -> "public_traffic_enabled";
          case REFERRERS -> "public_referrers_enabled";
          case PATHS -> "public_paths_enabled";
          case TOPICS -> "public_topics_enabled";
        };
    var ids =
        reads.scopedRepositoryIds(
            resolved.ownerId(), resolved.repositoryId(), flag, ageMillis());
    if (ids.isEmpty()) throw missing();
    return ids;
  }

  public Page repositories(String owner, long after, int size) {
    return repositories(owner, after, size, "7d");
  }

  public Page repositories(String owner, long after, int size, String rawPeriod) {
    var ids = scope(owner, null, PublicationService.Module.DASHBOARD);
    var range = period(ids, rawPeriod, true);
    LocalDate trafficTo =
        range.to().isBefore(LocalDate.now(ZoneOffset.UTC))
            ? range.to()
            : LocalDate.now(ZoneOffset.UTC).minusDays(1);
    // Traffic row values are observed period sums (same basis as owner KPI observedValue), not null
    // when a day is missing.
    var rows =
        reads.repositoryPage(
            ids,
            cursor(after),
            limit(size) + 1,
            range.from(),
            range.to(),
            trafficTo,
            ageMillis());
    var growth = visitorRows(ids, range);
    for (var row : rows) {
      long id = ((Number) row.get("id")).longValue();
      var match = growth.stream().filter(value -> value.repositoryId() == id).findFirst();
      row.put(
          "growthPercent",
          match.map(PortfolioHighlightMath.VisitorRow::growthPercent).orElse(null));
      Instant activityAt = instant(row.get("activityAt"));
      row.put("activityAt", activityAt);
      row.put(
          "activityStatus",
          activityClassifier.classify(Boolean.TRUE.equals(row.get("archived")), activityAt).name());
      var score =
          repositoryJdbc
              .findById(id)
              .map(healthService::discoverabilityScore)
              .orElse(new RepositoryHealthService.DiscoverabilityScore(null, null));
      row.put("discoverabilityPassed", score.passed());
      row.put("discoverabilityTotal", score.total());
    }
    return page(rows, size, "id");
  }

  private Instant instant(Object value) {
    if (value instanceof Instant instant) return instant;
    if (value instanceof OffsetDateTime offset) return offset.toInstant();
    if (value instanceof LocalDateTime local) return local.toInstant(ZoneOffset.UTC);
    if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant();
    return null;
  }

  @com.fasterxml.jackson.annotation.JsonInclude(
      value = com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS,
      content = com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
  public static class Fields extends LinkedHashMap<String, Object> {
    public Fields(Map<String, Object> row) {
      super(row);
    }
  }

  private List<Map<String, Object>> safeIds(List<Map<String, Object>> rows) {
    List<Map<String, Object>> result = new ArrayList<>();
    for (var row : rows) {
      var safe = new Fields(row);
      for (String key : List.of("id", "repositoryId"))
        if (safe.get(key) != null) safe.put(key, safe.get(key).toString());
      result.add(safe);
    }
    return result;
  }

  private void calendarDate(Map<String, Object> row) {
    Object date = row.get("date");
    if (date instanceof java.sql.Date sql) row.put("date", sql.toLocalDate());
    else if (date instanceof LocalDate local) row.put("date", local);
  }

  private Page page(List<Map<String, Object>> rows, int size, String key) {
    boolean more = rows.size() > size;
    var selected = more ? new ArrayList<>(rows.subList(0, size)) : rows;
    for (var row : selected) calendarDate(row);
    Long next = more ? ((Number) selected.getLast().get(key)).longValue() : null;
    return new Page(safeIds(selected), next);
  }

  private LocalDate publishedEnd(LocalDate to) {
    LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
    return to.isBefore(yesterday) ? to : yesterday;
  }

  private List<Map<String, Object>> fillDailyGaps(
      List<Map<String, Object>> rows, List<String> metrics) {
    if (rows.isEmpty()) return rows;
    NavigableMap<LocalDate, Map<String, Object>> byDate = new TreeMap<>();
    for (var row : rows) {
      calendarDate(row);
      byDate.put((LocalDate) row.get("date"), row);
    }
    List<Map<String, Object>> filled = new ArrayList<>();
    for (LocalDate day = byDate.firstKey(); !day.isAfter(byDate.lastKey()); day = day.plusDays(1)) {
      Map<String, Object> existing = byDate.get(day);
      if (existing != null) {
        filled.add(existing);
        continue;
      }
      Map<String, Object> hole = new LinkedHashMap<>();
      hole.put("date", day);
      hole.put("complete", false);
      hole.put("missingRepositories", null);
      for (String metric : metrics) hole.put(metric, null);
      filled.add(hole);
    }
    Collections.reverse(filled);
    return filled;
  }

  private DashboardPeriod period(List<Long> ids, String raw, boolean traffic) {
    return DashboardPeriod.of(
        raw, LocalDate.now(ZoneOffset.UTC), reads.earliestDate(ids, traffic));
  }

  public Map<String, Object> stats(String owner, String repository, String period) {
    var ids = scope(owner, repository, PublicationService.Module.DASHBOARD);
    var range = period(ids, period, false);
    var current = reads.latestDailyStats(ids, range.to());
    var previous =
        range.allTime()
            ? List.<Map<String, Object>>of()
            : reads.latestDailyStats(ids, range.previousTo());
    Map<String, Object> totals = new LinkedHashMap<>();
    for (String metric : List.of("stars", "forks", "watchers")) {
      long value = current.stream().mapToLong(row -> ((Number) row.get(metric)).longValue()).sum();
      boolean complete = current.size() == ids.size();
      long baseline =
          previous.stream().mapToLong(row -> ((Number) row.get(metric)).longValue()).sum();
      boolean comparable = !range.allTime() && complete && previous.size() == ids.size();
      totals.put(
          metric,
          new Metric(
              complete ? value : null,
              value,
              current.size(),
              ids.size(),
              complete,
              comparable ? value - baseline : null,
              comparable ? GrowthMath.percent(value, baseline) : null));
    }
    totals.put("contributors", tipContributorMetric(ids, current));
    return Map.of(
        "period",
        range.value(),
        "from",
        range.from(),
        "to",
        range.to(),
        "repositories",
        ids.size(),
        "totals",
        totals,
        "highlights",
        highlights(ids, range));
  }

  /**
   * Union of tip-day logins like admin Unique Contributors; complete=false when any tip misses
   * login rows.
   */
  private Metric tipContributorMetric(List<Long> ids, List<Map<String, Object>> tipRows) {
    if (tipRows.isEmpty()) return new Metric(null, 0, 0, ids.size(), false, null, null);
    Set<String> tipKeys = new HashSet<>();
    List<LocalDate> tipDates = new ArrayList<>();
    for (var row : tipRows) {
      LocalDate day = localDate(row.get("stat_date"));
      if (row.get("repository_id") == null || day == null) continue;
      tipKeys.add(((Number) row.get("repository_id")).longValue() + "|" + day);
      if (!tipDates.contains(day)) tipDates.add(day);
    }
    Map<String, Set<String>> loginsByTip = new HashMap<>();
    if (!tipDates.isEmpty()) {
      for (var row : reads.contributorLogins(ids, tipDates)) {
        LocalDate day = localDate(row.get("stat_date"));
        if (row.get("repository_id") == null || day == null || row.get("login") == null) continue;
        String key = ((Number) row.get("repository_id")).longValue() + "|" + day;
        if (!tipKeys.contains(key)) continue;
        loginsByTip
            .computeIfAbsent(key, ignored -> new HashSet<>())
            .add(String.valueOf(row.get("login")).toLowerCase(Locale.ROOT));
      }
    }
    Set<String> logins = new HashSet<>();
    long collected = 0;
    long complete = 0;
    for (var row : tipRows) {
      LocalDate day = localDate(row.get("stat_date"));
      if (row.get("repository_id") == null || day == null) continue;
      if (!Boolean.TRUE.equals(row.get("contributor_logins_collected"))) continue;
      collected++;
      Set<String> tipLogins =
          loginsByTip.getOrDefault(
              ((Number) row.get("repository_id")).longValue() + "|" + day, Set.of());
      logins.addAll(tipLogins);
      if (((Number) row.get("contributors")).intValue() == tipLogins.size()) complete++;
    }
    long known = logins.size();
    return new Metric(
        collected > 0 ? known : null,
        known,
        collected,
        ids.size(),
        complete == ids.size(),
        null,
        null);
  }

  private LocalDate localDate(Object value) {
    if (value instanceof LocalDate local) return local;
    if (value instanceof java.sql.Date sql) return sql.toLocalDate();
    return null;
  }

  private Map<String, Object> highlights(List<Long> ids, DashboardPeriod range) {
    var ranks =
        reads.successfulRanks(
            ids,
            range.to(),
            (rs, n) ->
                new com.kholodilin.repogrowth.search.model.SuccessfulRank(
                    rs.getLong("id"),
                    rs.getString("full_name"),
                    rs.getString("query"),
                    rs.getObject("business_date", LocalDate.class),
                    rs.getInt("tracked_repository_position")));
    var promotions =
        reads.promotionCounts(
            ids,
            range.from(),
            range.to(),
            (rs, n) ->
                new com.kholodilin.repogrowth.events.model.PromotionCount(
                    rs.getLong("id"), rs.getString("full_name"), rs.getInt("count")));
    var answer = new LinkedHashMap<String, Object>();
    answer.put(
        "rankRise", rankHighlight(PortfolioHighlightMath.strongestRise(ranks, range.from())));
    answer.put("rankDrop", rankHighlight(PortfolioHighlightMath.worstDrop(ranks, range.from())));
    var events = PortfolioHighlightMath.promotionEvents(promotions);
    answer.put(
        "events",
        events == null
            ? null
            : Map.of(
                "count",
                events.count(),
                "repositoryId",
                Long.toString(events.repositoryId()),
                "fullName",
                events.fullName(),
                "repositoryNames",
                events.repositoryNames()));
    var visitors = PortfolioHighlightMath.strongestVisitorGrowth(visitorRows(ids, range));
    answer.put(
        "visitors",
        visitors == null
            ? null
            : Map.of(
                "repositoryId",
                Long.toString(visitors.repositoryId()),
                "fullName",
                visitors.fullName(),
                "growthPercent",
                visitors.growthPercent()));
    return new Fields(answer);
  }

  private List<PortfolioHighlightMath.VisitorRow> visitorRows(
      List<Long> ids, DashboardPeriod range) {
    if (range.allTime()) return List.of();
    LocalDate to =
        range.to().isBefore(LocalDate.now(ZoneOffset.UTC))
            ? range.to()
            : LocalDate.now(ZoneOffset.UTC).minusDays(1);
    long days = java.time.temporal.ChronoUnit.DAYS.between(range.from(), to) + 1;
    if (days <= 0) return List.of();
    LocalDate previousTo = range.previousTo();
    LocalDate previousFrom = previousTo.minusDays(days - 1);
    return reads.visitorRows(
        ids,
        range.from(),
        to,
        previousFrom,
        previousTo,
        (rs, n) -> {
          Long value = rs.getLong("observed") == days ? rs.getLong("visitors") : null;
          Double growth =
              value != null && rs.getLong("prior_observed") == days
                  ? GrowthMath.percent(value, rs.getLong("prior"))
                  : null;
          return new PortfolioHighlightMath.VisitorRow(
              rs.getLong("id"), rs.getString("full_name"), value, growth);
        });
  }

  private Object rankHighlight(
      com.kholodilin.repogrowth.catalog.service.DashboardResponse.RankHighlight value) {
    return value == null
        ? null
        : Map.of(
            "repositoryId",
            Long.toString(value.repositoryId()),
            "fullName",
            value.fullName(),
            "queryName",
            value.queryName(),
            "fromRank",
            value.fromRank(),
            "toRank",
            value.toRank());
  }

  public Page statsHistory(
      String owner, String repository, String period, LocalDate before, int size) {
    var ids = scope(owner, repository, PublicationService.Module.DASHBOARD);
    var range = period(ids, period, false);
    LocalDate to = before == null ? range.to() : before.minusDays(1);
    var rows = reads.statsHistory(ids, range.from(), to, limit(size) + 1);
    if (!rows.isEmpty()) {
      var dates = rows.stream().map(row -> row.get("date")).toList();
      var counts = reads.statsHistoryContributorCounts(ids, dates, ids.size());
      for (var row : rows) {
        var contributors =
            counts.stream()
                .filter(value -> value.get("date").equals(row.get("date")))
                .findFirst()
                .orElseThrow();
        row.put(
            "contributors",
            Boolean.TRUE.equals(contributors.get("complete"))
                ? contributors.get("contributors")
                : null);
        row.put("contributorsComplete", contributors.get("complete"));
      }
    }
    for (var row : rows) {
      boolean complete = ((Number) row.get("observed")).longValue() == ids.size();
      row.put("complete", complete);
      row.put("missingRepositories", ids.size() - ((Number) row.get("observed")).intValue());
    }
    var page =
        historyPage(
            rows, size, before == null, List.of("stars", "forks", "watchers", "contributors"));
    attachStatLeaders(ids, page.items());
    return page;
  }

  public Map<String, Object> traffic(String owner, String repository, String period) {
    var ids = scope(owner, repository, PublicationService.Module.TRAFFIC);
    var range = period(ids, period, true);
    LocalDate to = trafficKpiEnd(ids, range);
    var result = reads.trafficTotals(ids, range.from(), to);
    long expected =
            Math.max(0, java.time.temporal.ChronoUnit.DAYS.between(range.from(), to) + 1)
                * ids.size(),
        observed = ((Number) result.get("observed")).longValue();
    Map<String, Object> prior = new HashMap<>();
    if (!range.allTime() && expected > 0) {
      LocalDate previousTo = range.previousTo();
      LocalDate previousFrom = previousTo.minusDays(expected / ids.size() - 1);
      prior = reads.trafficTotals(ids, previousFrom, previousTo);
    }
    boolean comparable =
        !prior.isEmpty()
            && ((Number) prior.get("observed")).longValue() == expected
            && observed == expected;
    Map<String, Object> metrics = new LinkedHashMap<>();
    for (String metric : List.of("views", "visitors", "clones", "uniqueCloners")) {
      long value = ((Number) result.get(metric)).longValue();
      long baseline = comparable ? ((Number) prior.get(metric)).longValue() : 0;
      metrics.put(
          metric,
          new Metric(
              observed == expected && expected > 0 ? value : null,
              value,
              observed,
              expected,
              observed == expected && expected > 0,
              comparable ? value - baseline : null,
              comparable ? GrowthMath.percent(value, baseline) : null));
    }
    Map<String, Object> answer = new LinkedHashMap<>();
    answer.put("period", range.value());
    answer.put("from", range.from());
    answer.put("to", range.to());
    answer.put("totals", metrics);
    answer.put(
        "coverage", Map.of("observedRepositoryDays", observed, "expectedRepositoryDays", expected));
    if (!prior.isEmpty())
      answer.put(
          "previousCoverage",
          Map.of(
              "observedRepositoryDays",
              ((Number) prior.get("observed")).longValue(),
              "expectedRepositoryDays",
              expected));
    return answer;
  }

  /** Same as admin Portfolio KPI: drop incomplete T−1 from multi-day windows; keep 1d honest. */
  private LocalDate trafficKpiEnd(List<Long> ids, DashboardPeriod range) {
    LocalDate end = publishedEnd(range.to());
    if (range.allTime()
        || "1d".equals(range.value())
        || ids.isEmpty()
        || end.isBefore(range.from())) return end;
    Long tip = reads.trafficTipCount(ids, end);
    if (tip != null && tip < ids.size()) {
      LocalDate prior = end.minusDays(1);
      return prior.isBefore(range.from()) ? end : prior;
    }
    return end;
  }

  public Page trafficHistory(
      String owner, String repository, String period, LocalDate before, int size) {
    var ids = scope(owner, repository, PublicationService.Module.TRAFFIC);
    var range = period(ids, period, true);
    LocalDate to = before == null ? publishedEnd(range.to()) : before.minusDays(1);
    var rows = reads.trafficHistory(ids, range.from(), to, limit(size) + 1);
    for (var row : rows) {
      boolean complete = ((Number) row.get("observed")).longValue() == ids.size();
      row.put("complete", complete);
      row.put("missingRepositories", ids.size() - ((Number) row.get("observed")).intValue());
    }
    var page =
        historyPage(
            rows, size, before == null, List.of("views", "visitors", "clones", "uniqueCloners"));
    attachTrafficLeaders(ids, page.items());
    return page;
  }

  /** Top-3 positive shares per day — same honesty as admin Portfolio chart tooltips. */
  private void attachTrafficLeaders(List<Long> ids, List<Map<String, Object>> items) {
    if (items.isEmpty()) return;
    List<LocalDate> dates = distinctItemDates(items);
    if (dates.isEmpty()) return;
    var byDate = sharesByDate(reads.trafficShares(ids, dates));
    for (var row : items) {
      List<Map<String, Object>> day = byDate.getOrDefault(localDate(row.get("date")), List.of());
      row.put("viewsLeaders", topLeaders(day, "views"));
      row.put("visitorsLeaders", topLeaders(day, "visitors"));
      row.put("clonesLeaders", topLeaders(day, "clones"));
    }
  }

  private void attachStatLeaders(List<Long> ids, List<Map<String, Object>> items) {
    if (items.isEmpty()) return;
    List<LocalDate> dates = distinctItemDates(items);
    if (dates.isEmpty()) return;
    var byDate = sharesByDate(reads.statShares(ids, dates));
    for (var row : items) {
      List<Map<String, Object>> day = byDate.getOrDefault(localDate(row.get("date")), List.of());
      row.put("starsLeaders", topLeaders(day, "stars"));
      row.put("forksLeaders", topLeaders(day, "forks"));
      row.put("watchersLeaders", topLeaders(day, "watchers"));
      row.put("contributorsLeaders", topLeaders(day, "contributors"));
    }
  }

  private List<LocalDate> distinctItemDates(List<Map<String, Object>> items) {
    return items.stream()
        .map(row -> localDate(row.get("date")))
        .filter(Objects::nonNull)
        .distinct()
        .toList();
  }

  private Map<LocalDate, List<Map<String, Object>>> sharesByDate(List<Map<String, Object>> shares) {
    Map<LocalDate, List<Map<String, Object>>> byDate = new HashMap<>();
    for (var share : shares) {
      LocalDate day = localDate(share.get("date"));
      if (day != null) byDate.computeIfAbsent(day, ignored -> new ArrayList<>()).add(share);
    }
    return byDate;
  }

  private List<Map<String, Object>> topLeaders(List<Map<String, Object>> shares, String metric) {
    return shares.stream()
        .map(share ->
            Map.<String, Object>of(
                "fullName",
                String.valueOf(share.get("fullName")),
                "value",
                share.get(metric) == null ? 0L : ((Number) share.get(metric)).longValue()))
        .filter(leader -> ((Number) leader.get("value")).longValue() > 0)
        .sorted(
            Comparator.<Map<String, Object>>comparingLong(
                    leader -> ((Number) leader.get("value")).longValue())
                .reversed()
                .thenComparing(leader -> String.valueOf(leader.get("fullName"))))
        .limit(3)
        .toList();
  }

  private Page historyPage(
      List<Map<String, Object>> rows, int size, boolean fillGaps, List<String> metrics) {
    boolean more = rows.size() > size;
    List<Map<String, Object>> selected =
        more ? new ArrayList<>(rows.subList(0, size)) : new ArrayList<>(rows);
    for (var row : selected) calendarDate(row);
    LocalDate next = more ? (LocalDate) selected.getLast().get("date") : null;
    if (fillGaps) selected = fillDailyGaps(selected, metrics);
    return new Page(safeIds(selected), null, next);
  }

  public Page sources(String owner, String repository, boolean paths, long after, int size) {
    var ids =
        scope(
            owner,
            repository,
            paths ? PublicationService.Module.PATHS : PublicationService.Module.REFERRERS);
    return page(
        reads.latestSources(ids, paths, cursor(after), limit(size) + 1), size, "id");
  }

  public Page definitions(String owner, String repository, boolean topics, long after, int size) {
    var ids =
        scope(
            owner,
            repository,
            topics ? PublicationService.Module.TOPICS : PublicationService.Module.DASHBOARD);
    return page(
        reads.definitions(ids, topics, cursor(after), limit(size) + 1), size, "id");
  }

  private long parent(boolean topics, long id, boolean run) {
    var rows = reads.parentDefinitionOrRun(topics, id, run);
    if (rows.isEmpty()) throw missing();
    long repository = ((Number) rows.getFirst().get("repository_id")).longValue();
    if (topics
        ? !policy.allows(repository, PublicationService.Module.TOPICS)
        : !policy.queryEligible(((Number) rows.getFirst().get("definition")).longValue()))
      throw missing();
    return repository;
  }

  public void validateDefinition(String owner, String repository, boolean topics, long id) {
    long parent = parent(topics, id, false);
    if (!scope(owner, repository, PublicationService.Module.DASHBOARD).contains(parent))
      throw missing();
  }

  public long validateTopic(String owner, String repository, String topic) {
    if (topic == null || topic.isBlank() || topic.length() > 200) throw missing();
    var ids = scope(owner, repository, PublicationService.Module.TOPICS);
    var rows = reads.topicWatchByTopic(ids, topic);
    if (rows.isEmpty()) throw missing();
    return ((Number) rows.getFirst().get("id")).longValue();
  }

  public Map<String, Object> definition(boolean topics, long id) {
    parent(topics, id, false);
    return safeIds(reads.definitionById(topics, id)).getFirst();
  }

  public Page rankHistory(boolean topics, long definition, long before, int size) {
    parent(topics, definition, false);
    return page(
        reads.rankHistory(topics, definition, cursor(before), limit(size) + 1), size, "id");
  }

  public Page rankResults(boolean topics, long run, long after, int size) {
    parent(topics, run, true);
    if (!Boolean.TRUE.equals(reads.runPubliclyVerified(topics, run))) throw missing();
    return page(
        reads.rankResults(topics, run, cursor(after), limit(size) + 1), size, "id");
  }

  public Page events(String owner, String repository, long before, int size) {
    var ids = scope(owner, repository, PublicationService.Module.DASHBOARD);
    return page(
        reads.events(ids, cursor(before), limit(size) + 1), size, "id");
  }

  public Page sourceHistory(
      String owner, String repository, boolean paths, String period, long before, int size) {
    var ids =
        scope(
            owner,
            repository,
            paths ? PublicationService.Module.PATHS : PublicationService.Module.REFERRERS);
    var range = period(ids, period, true);
    return page(
        reads.sourceHistory(
            ids,
            paths,
            range.from(),
            publishedEnd(range.to()),
            cursor(before),
            limit(size) + 1),
        size,
        "id");
  }

  public Page rankSeries(boolean topics, long definition, long before, int size) {
    parent(topics, definition, false);
    return page(
        reads.rankSeries(topics, definition, cursor(before), limit(size) + 1), size, "id");
  }

  public Object competitor(boolean topics, long definition, long githubId) {
    parent(topics, definition, false);
    var rows = reads.competitor(topics, definition, githubId);
    if (rows.isEmpty()) throw missing();
    var answer = rows.getFirst();
    answer.put("metadataComplete", false);
    var profiles = reads.competitorProfile(githubId);
    if (!profiles.isEmpty()) {
      answer.put("profile", profiles.getFirst());
      answer.put("metadataComplete", true);
    }
    return answer;
  }

  public Object latestResults(boolean topics, long definition, long after, int size) {
    parent(topics, definition, false);
    var ids = reads.latestPublicRunId(topics, definition);
    if (ids.isEmpty()) throw missing();
    return rankResults(topics, ids.getFirst(), after, size);
  }

  public Object overview(String owner, String repository) {
    var resolved = resolve("/" + owner + (repository == null ? "" : "/" + repository));
    var ids = scope(owner, repository, PublicationService.Module.DASHBOARD);
    var metadata = reads.ownerMetadata(resolved.ownerId());
    metadata.put("id", Long.toString(resolved.ownerId()));
    Map<String, Object> answer = new LinkedHashMap<>();
    answer.put("owner", metadata);
    answer.put("canonicalPath", resolved.canonicalPath());
    answer.put("repositoryCount", ids.size());
    var modules = reads.moduleFlags(ids);
    modules.put("search", reads.hasPublicSearch(ids));
    answer.put("modules", modules);
    java.sql.Timestamp stamp = reads.maxEnrichedAt(ids);
    var freshness = new LinkedHashMap<String, Object>();
    freshness.put("lastSuccessfulSnapshotAt", stamp == null ? null : stamp.toInstant());
    freshness.put("state", stamp == null ? "EMPTY" : "READY");
    answer.put("freshness", freshness);
    if (repository != null) {
      var item = reads.repositoryCard(resolved.repositoryId());
      item.put("id", resolved.repositoryId().toString());
      item.put("pathPrefixes", reads.repositoryPathPrefixes(resolved.repositoryId()));
      answer.put("repository", item);
    }
    return answer;
  }

  public Page owners(String sort, String order, long offset, int size) {
    String column =
        Map.of(
                "owner",
                "login",
                "repositories",
                "repositories",
                "stars",
                "stars",
                "forks",
                "forks",
                "watchers",
                "watchers",
                "contributors",
                "contributors")
            .get(sort);
    if (column == null || !Set.of("asc", "desc").contains(order) || offset < 0 || offset > 10000)
      throw ApiException.validation("Invalid directory pagination or sort");
    var rows =
        reads.ownersDirectory(
            column, order, offset, limit(size) + 1, ageMillis(), PublicRoutes.RESERVED);
    boolean more = rows.size() > size;
    return new Page(safeIds(more ? rows.subList(0, size) : rows), more ? offset + size : null);
  }
}
