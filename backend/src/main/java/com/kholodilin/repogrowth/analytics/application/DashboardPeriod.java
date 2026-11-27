package com.kholodilin.repogrowth.analytics.application;

import com.kholodilin.repogrowth.common.api.PeriodParser;
import com.kholodilin.repogrowth.common.model.period.DateRange;
import com.kholodilin.repogrowth.common.model.period.Period;
import com.kholodilin.repogrowth.common.model.period.PeriodRanges;
import java.time.LocalDate;

public record DashboardPeriod(
    String value, LocalDate from, LocalDate to, LocalDate previousFrom, LocalDate previousTo) {

  public static final String DEFAULT = Period.DEFAULT.wire();

  public static DashboardPeriod of(String raw, LocalDate today, LocalDate earliestTrafficDate) {
    Period period = PeriodParser.parse(raw);
    DateRange range = PeriodRanges.resolve(period, today, earliestTrafficDate);
    if (period.allTime()) {
      return new DashboardPeriod(period.wire(), range.from(), range.to(), null, null);
    }
    var comparison = PeriodRanges.comparison(range).orElseThrow();
    return new DashboardPeriod(
        period.wire(), range.from(), range.to(), comparison.from(), comparison.to());
  }

  public boolean allTime() {
    return Period.ALL.wire().equals(value);
  }

  /**
   * Null/blank become the product default ({@value #DEFAULT}). Any other unsupported value is a
   * validation error — never a silent fallback to another period.
   */
  public static String normalize(String raw) {
    return PeriodParser.parse(raw).wire();
  }
}
