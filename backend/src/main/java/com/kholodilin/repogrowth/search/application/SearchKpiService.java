package com.kholodilin.repogrowth.search.application;

import com.kholodilin.repogrowth.auth.security.ResourceAccessPolicy;
import com.kholodilin.repogrowth.common.api.ApiException;
import com.kholodilin.repogrowth.common.api.PeriodParser;
import com.kholodilin.repogrowth.common.model.kpi.DataStatus;
import com.kholodilin.repogrowth.common.model.kpi.KpiChange;
import com.kholodilin.repogrowth.common.model.kpi.KpiScope;
import com.kholodilin.repogrowth.common.model.kpi.KpiValue;
import com.kholodilin.repogrowth.common.model.kpi.Unit;
import com.kholodilin.repogrowth.common.model.period.ComparisonRange;
import com.kholodilin.repogrowth.common.model.period.DateRange;
import com.kholodilin.repogrowth.common.model.period.Period;
import com.kholodilin.repogrowth.common.model.period.PeriodRanges;
import com.kholodilin.repogrowth.publication.PublicationService;
import com.kholodilin.repogrowth.repository.application.RepositoryService;
import com.kholodilin.repogrowth.repository.domain.Repository;
import com.kholodilin.repogrowth.search.api.SearchKpiResponse;
import com.kholodilin.repogrowth.search.application.BandKpiMath.BandKpi;
import com.kholodilin.repogrowth.search.application.BandKpiMath.DayMode;
import com.kholodilin.repogrowth.search.application.BandKpiMath.RankPoint;
import com.kholodilin.repogrowth.search.application.BandKpiMath.RankSample;
import com.kholodilin.repogrowth.search.application.SearchQueryService.SearchHistory;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class SearchKpiService {

  /** Parity with frontend {@code SEARCH_QUERY_LIMIT}. */
  private static final int SEARCH_QUERY_LIMIT = 50;

  private final ResourceAccessPolicy access;
  private final RepositoryService repositories;
  private final SearchQueryService searchQueries;
  private final Clock clock;

  public SearchKpiService(
      ResourceAccessPolicy access,
      RepositoryService repositories,
      SearchQueryService searchQueries,
      Clock clock) {
    this.access = access;
    this.repositories = repositories;
    this.searchQueries = searchQueries;
    this.clock = clock;
  }

  public SearchKpiResponse repositoryKpi(long repositoryId, String periodParam) {
    access.requireReadable(repositoryId, PublicationService.Module.DASHBOARD);
    List<SearchHistory> histories = searchQueries.visibility(repositoryId);
    return build(KpiScope.REPOSITORY, Long.toString(repositoryId), histories, periodParam);
  }

  public SearchKpiResponse ownerKpi(long ownerId, String periodParam) {
    List<Long> readable = readableOwnerRepositories(ownerId);
    if (readable.isEmpty()) {
      throw ApiException.notFound("Owner not found");
    }
    List<SearchHistory> histories = new ArrayList<>();
    for (Long repositoryId : readable) {
      histories.addAll(searchQueries.visibility(repositoryId));
    }
    return build(KpiScope.OWNER, Long.toString(ownerId), histories, periodParam);
  }

  /**
   * Batch histories for a repository. Caps the number of returned query histories to avoid N+1
   * blow-ups when replacing {@code search-visibility}.
   */
  public List<SearchHistory> repositoryQueriesWithHistory(
      long repositoryId, boolean includeHistory, int limit) {
    access.requireReadable(repositoryId, PublicationService.Module.DASHBOARD);
    int capped = Math.min(Math.max(limit, 1), 100);
    if (!includeHistory) {
      return searchQueries.list(repositoryId).stream()
          .limit(capped)
          .map(query -> searchQueries.history(query.id()))
          .toList();
    }
    return searchQueries.visibility(repositoryId).stream().limit(capped).toList();
  }

  private SearchKpiResponse build(
      KpiScope scope, String scopeId, List<SearchHistory> histories, String periodParam) {
    Period period = PeriodParser.parse(periodParam);
    LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
    DateRange range = PeriodRanges.resolve(period, today, earliest(histories).orElse(today));
    ComparisonRange comparison =
        period.allTime() ? null : PeriodRanges.comparison(range).orElse(null);
    List<RankSample> samples =
        histories.stream()
            .map(history ->
                new RankSample(
                    history.query().id(),
                    history.query().enabled(),
                    history.currentRank(),
                    history.points().stream()
                        .map(point -> new RankPoint(point.date(), point.position()))
                        .toList()))
            .toList();
    BandKpi top3 = BandKpiMath.bandKpi(samples, 3, period, today, DayMode.STRICT_DAY);
    BandKpi top10 = BandKpiMath.bandKpi(samples, 10, period, today, DayMode.STRICT_DAY);
    BandKpi top20 = BandKpiMath.bandKpi(samples, 20, period, today, DayMode.STRICT_DAY);
    // Owner usage counts query rows across readable repositories against the same per-repo limit.
    int used = histories.size();
    int queryLimit =
        scope == KpiScope.OWNER ? Math.max(used, SEARCH_QUERY_LIMIT) : SEARCH_QUERY_LIMIT;
    var usage = BandKpiMath.usage(used, queryLimit);
    return new SearchKpiResponse(
        scope,
        scopeId,
        period,
        range,
        comparison,
        new SearchKpiResponse.Metrics(toKpi(top3), toKpi(top10), toKpi(top20)),
        new SearchKpiResponse.Usage(
            usage.used(), usage.limit(), usage.available(), usage.progress()));
  }

  private static KpiValue toKpi(BandKpi band) {
    if (band.value() == null) {
      return new KpiValue(null, Unit.COUNT, DataStatus.MISSING, null, null, null);
    }
    KpiChange change = null;
    if (band.absoluteChange() != null || band.growthPercent() != null) {
      change =
          new KpiChange(
              band.absoluteChange() == null ? null : BigDecimal.valueOf(band.absoluteChange()),
              band.growthPercent() == null ? null : BigDecimal.valueOf(band.growthPercent()));
    }
    return new KpiValue(
        BigDecimal.valueOf(band.value()), Unit.COUNT, DataStatus.COMPLETE, change, null, null);
  }

  private static java.util.Optional<LocalDate> earliest(List<SearchHistory> histories) {
    return histories.stream()
        .flatMap(history -> history.points().stream())
        .map(SearchQueryService.RankPoint::date)
        .min(LocalDate::compareTo);
  }

  private List<Long> readableOwnerRepositories(long ownerId) {
    repositories.owner(ownerId);
    return access.filterReadable(
        repositories.list(false).stream()
            .filter(repo -> repo.ownerId() == ownerId)
            .map(Repository::id)
            .toList(),
        PublicationService.Module.DASHBOARD);
  }
}
