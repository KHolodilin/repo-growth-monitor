package com.kholodilin.repogrowth.topics.service;

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
import com.kholodilin.repogrowth.search.service.BandKpiMath;
import com.kholodilin.repogrowth.search.service.BandKpiMath.BandKpi;
import com.kholodilin.repogrowth.search.service.BandKpiMath.DayMode;
import com.kholodilin.repogrowth.search.service.BandKpiMath.RankPoint;
import com.kholodilin.repogrowth.search.service.BandKpiMath.RankSample;
import com.kholodilin.repogrowth.topics.service.TopicVisibilityService.TopicHistory;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

/** Topics KPI and watch-history reads. Access checks belong in the controller. */
@Service
public class TopicsKpiService {

  private final TopicVisibilityService topics;
  private final Clock clock;

  public TopicsKpiService(TopicVisibilityService topics, Clock clock) {
    this.topics = topics;
    this.clock = clock;
  }

  public TopicsKpiResponse repositoryKpi(long repositoryId, String periodParam, String scope) {
    String topicScope = normalizeScope(scope);
    List<TopicHistory> histories = topics.visibility(repositoryId, topicScope);
    return build(
        KpiScope.REPOSITORY, Long.toString(repositoryId), histories, periodParam, topicScope);
  }

  public TopicsKpiResponse ownerKpi(
      long ownerId, List<Long> readableRepositoryIds, String periodParam, String scope) {
    String topicScope = normalizeScope(scope);
    List<TopicHistory> histories = new ArrayList<>();
    for (Long repositoryId : readableRepositoryIds) {
      histories.addAll(topics.visibility(repositoryId, topicScope));
    }
    return build(KpiScope.OWNER, Long.toString(ownerId), histories, periodParam, topicScope);
  }

  public List<TopicHistory> repositoryWatchesWithHistory(
      long repositoryId, String scope, boolean includeHistory, int limit) {
    int capped = Math.min(Math.max(limit, 1), 100);
    String topicScope = normalizeScope(scope);
    List<TopicHistory> histories = topics.visibility(repositoryId, topicScope);
    if (!includeHistory) {
      return histories.stream().limit(capped).toList();
    }
    return histories.stream().limit(capped).toList();
  }

  private TopicsKpiResponse build(
      KpiScope scope,
      String scopeId,
      List<TopicHistory> histories,
      String periodParam,
      String topicScope) {
    Period period = PeriodParser.parse(periodParam);
    LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
    DateRange range = PeriodRanges.resolve(period, today, earliest(histories).orElse(today));
    ComparisonRange comparison =
        period.allTime() ? null : PeriodRanges.comparison(range).orElse(null);
    List<RankSample> samples =
        histories.stream()
            .map(history ->
                new RankSample(
                    history.watch().id(),
                    true,
                    history.currentRank(),
                    history.points().stream()
                        .map(point -> new RankPoint(point.date(), point.position()))
                        .toList()))
            .toList();
    BandKpi top3 = BandKpiMath.bandKpi(samples, 3, period, today, DayMode.CARRY_FORWARD);
    BandKpi top10 = BandKpiMath.bandKpi(samples, 10, period, today, DayMode.CARRY_FORWARD);
    BandKpi top20 = BandKpiMath.bandKpi(samples, 20, period, today, DayMode.CARRY_FORWARD);
    return new TopicsKpiResponse(
        scope,
        scopeId,
        period,
        range,
        comparison,
        topicScope,
        new TopicsKpiResponse.Metrics(toKpi(top3), toKpi(top10), toKpi(top20)));
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

  private static java.util.Optional<LocalDate> earliest(List<TopicHistory> histories) {
    return histories.stream()
        .flatMap(history -> history.points().stream())
        .map(TopicVisibilityService.RankPoint::date)
        .min(LocalDate::compareTo);
  }

  private static String normalizeScope(String scope) {
    if (scope == null || scope.isBlank()) {
      return "all";
    }
    String normalized = scope.trim().toLowerCase(Locale.ROOT);
    if ("all".equals(normalized) || "language".equals(normalized)) {
      return normalized;
    }
    throw ApiException.validation("Unsupported topic scope: " + scope);
  }
}
