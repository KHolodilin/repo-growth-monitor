package com.kholodilin.repogrowth.traffic.api;

import com.kholodilin.repogrowth.common.model.kpi.KpiScope;
import com.kholodilin.repogrowth.common.model.kpi.KpiValue;
import com.kholodilin.repogrowth.common.model.period.ComparisonRange;
import com.kholodilin.repogrowth.common.model.period.DateRange;
import com.kholodilin.repogrowth.common.model.period.Period;

/**
 * Typed Traffic KPI payload for the unified resource API. Metrics are named fields — never a {@code
 * Map<String,Object>}.
 */
public record TrafficKpiResponse(
    KpiScope scope,
    String scopeId,
    Period period,
    DateRange range,
    ComparisonRange comparison,
    Metrics metrics) {

  public record Metrics(
      KpiValue views, KpiValue visitors, KpiValue clones, KpiValue uniqueCloners) {}
}
