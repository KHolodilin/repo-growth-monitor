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

  public record Metrics(KpiValue top3, KpiValue top10, KpiValue top20) {}

  public record Usage(int used, int limit, int available, double progress) {}

  public record BandSeries(String band, List<Integer> series) {}
}
