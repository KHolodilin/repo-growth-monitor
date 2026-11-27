package com.kholodilin.repogrowth.common.model.period;

import java.time.LocalDate;

/** Inclusive calendar range for a selected {@link Period}. */
public record DateRange(LocalDate from, LocalDate to) {

  public DateRange {
    if (from == null || to == null) {
      throw new IllegalArgumentException("DateRange requires from and to");
    }
    if (to.isBefore(from)) {
      throw new IllegalArgumentException("DateRange to must be on or after from");
    }
  }
}
