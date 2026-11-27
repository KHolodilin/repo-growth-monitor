package com.kholodilin.repogrowth.common.model.kpi;

/**
 * Observed vs expected coverage. {@code unit} describes the coverage unit (for example traffic
 * days), not the KPI metric unit.
 */
public record Coverage(long observed, long expected, String unit) {

  public Coverage {
    if (observed < 0 || expected < 0) {
      throw new IllegalArgumentException("Coverage counts must be non-negative");
    }
    if (unit == null || unit.isBlank()) {
      throw new IllegalArgumentException("Coverage unit is required");
    }
  }
}
