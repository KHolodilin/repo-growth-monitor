package com.kholodilin.repogrowth.common.model.kpi;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Shared KPI value contract. Prefer exact decimals/integers over double. Target KPI responses use
 * typed fields — never {@code Map<String,Object>} metric bags.
 */
public record KpiValue(
    BigDecimal value,
    Unit unit,
    DataStatus dataStatus,
    KpiChange change,
    Coverage coverage,
    Instant asOf) {

  public KpiValue {
    if (unit == null) {
      throw new IllegalArgumentException("unit is required");
    }
    if (dataStatus == null) {
      throw new IllegalArgumentException("dataStatus is required");
    }
  }
}
