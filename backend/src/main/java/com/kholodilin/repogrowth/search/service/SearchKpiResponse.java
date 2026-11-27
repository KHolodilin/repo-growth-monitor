package com.kholodilin.repogrowth.search.service;

import com.kholodilin.repogrowth.common.model.kpi.KpiScope;
import com.kholodilin.repogrowth.common.model.kpi.KpiValue;
import com.kholodilin.repogrowth.common.model.period.ComparisonRange;
import com.kholodilin.repogrowth.common.model.period.DateRange;
import com.kholodilin.repogrowth.common.model.period.Period;
import java.util.List;

/** Typed Search KPI payload — Top 3/10/20 plus query-usage block. */
public record SearchKpiResponse(
    KpiScope scope,
    String scopeId,
    Period period,
    DateRange range,
    ComparisonRange comparison,
    Metrics metrics,
    Usage usage) {

  /** Band metric with sparkline series aligned to {@link #range()} days. */
  public record BandMetric(KpiValue kpi, List<Integer> series) {}

  public record Metrics(BandMetric top3, BandMetric top10, BandMetric top20) {}

  public record Usage(int used, int limit, int available, double progress) {}
}
