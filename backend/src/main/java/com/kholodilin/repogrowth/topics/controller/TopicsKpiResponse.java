package com.kholodilin.repogrowth.topics.controller;

import com.kholodilin.repogrowth.common.model.kpi.KpiScope;
import com.kholodilin.repogrowth.common.model.kpi.KpiValue;
import com.kholodilin.repogrowth.common.model.period.ComparisonRange;
import com.kholodilin.repogrowth.common.model.period.DateRange;
import com.kholodilin.repogrowth.common.model.period.Period;

/** Typed Topics KPI payload — Top 3/10/20 for a scope (ALL / LANGUAGE). */
public record TopicsKpiResponse(
    KpiScope scope,
    String scopeId,
    Period period,
    DateRange range,
    ComparisonRange comparison,
    String topicScope,
    Metrics metrics) {

  public record Metrics(KpiValue top3, KpiValue top10, KpiValue top20) {}
}
