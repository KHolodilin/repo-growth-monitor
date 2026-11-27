package com.kholodilin.repogrowth.topics.service;

import com.kholodilin.repogrowth.common.model.kpi.KpiScope;
import com.kholodilin.repogrowth.common.model.kpi.KpiValue;
import com.kholodilin.repogrowth.common.model.period.ComparisonRange;
import com.kholodilin.repogrowth.common.model.period.DateRange;
import com.kholodilin.repogrowth.common.model.period.Period;
import java.util.List;

/** Typed Topics KPI payload — Top 3/10/20 for a scope (ALL / LANGUAGE). */
public record TopicsKpiResponse(
    KpiScope scope,
    String scopeId,
    Period period,
    DateRange range,
    ComparisonRange comparison,
    String topicScope,
    Metrics metrics) {

  /** Band metric with sparkline series aligned to {@link #range()} days. */
  public record BandMetric(KpiValue kpi, List<Integer> series) {}

  public record Metrics(BandMetric top3, BandMetric top10, BandMetric top20) {}
}
