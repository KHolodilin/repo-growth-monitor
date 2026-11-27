package com.kholodilin.repogrowth.common.model.period;

import java.time.LocalDate;

/** Inclusive previous window of equal length, or absent for all-time. */
public record ComparisonRange(LocalDate from, LocalDate to) {

  public ComparisonRange {
    if (from == null || to == null) {
      throw new IllegalArgumentException("ComparisonRange requires from and to");
    }
    if (to.isBefore(from)) {
      throw new IllegalArgumentException("ComparisonRange to must be on or after from");
    }
  }
}
