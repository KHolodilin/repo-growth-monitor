package com.kholodilin.repogrowth.stats.controller;

import com.kholodilin.repogrowth.common.model.kpi.KpiScope;
import com.kholodilin.repogrowth.common.model.kpi.KpiValue;
import com.kholodilin.repogrowth.common.model.period.ComparisonRange;
import com.kholodilin.repogrowth.common.model.period.DateRange;
import com.kholodilin.repogrowth.common.model.period.Period;

/**
 * Typed Stats KPI payload for the unified resource API. Metrics are named fields — never a {@code
 * Map<String,Object>}.
 */
public record StatsKpiResponse(
    KpiScope scope,
    String scopeId,
    Period period,
    DateRange range,
    ComparisonRange comparison,
    Metrics metrics) {

  public record Metrics(KpiValue stars, KpiValue forks, KpiValue watchers, KpiValue contributors) {}
}
