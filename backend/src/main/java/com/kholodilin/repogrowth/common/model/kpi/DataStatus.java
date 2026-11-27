package com.kholodilin.repogrowth.common.model.kpi;

/** Observation completeness for a KPI value — not derived mechanically from null/zero. */
public enum DataStatus {
  COMPLETE,
  PARTIAL,
  MISSING,
  NOT_APPLICABLE
}
