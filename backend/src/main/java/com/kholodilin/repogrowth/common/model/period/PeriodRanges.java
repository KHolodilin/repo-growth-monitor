package com.kholodilin.repogrowth.common.model.period;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/** Resolves inclusive {@link DateRange} / {@link ComparisonRange} for a {@link Period}. */
public final class PeriodRanges {

  private PeriodRanges() {}

  public static DateRange resolve(Period period, LocalDate today, LocalDate earliestInclusive) {
    if (period == null) {
      throw new IllegalArgumentException("period is required");
    }
    if (today == null) {
      throw new IllegalArgumentException("today is required");
    }
    if (period.allTime()) {
      LocalDate from = earliestInclusive != null ? earliestInclusive : today;
      return new DateRange(from, today);
    }
    LocalDate from =
        switch (period) {
          case D1 -> today.minusDays(1);
          case D7 -> today.minusDays(6);
          case D30 -> today.minusDays(29);
          case D90 -> today.minusDays(89);
          case Y1 -> today.minusYears(1).plusDays(1);
          case ALL -> today;
        };
    LocalDate to = period == Period.D1 ? from : today;
    return new DateRange(from, to);
  }

  public static Optional<ComparisonRange> comparison(DateRange range) {
    if (range == null) {
      throw new IllegalArgumentException("range is required");
    }
    long days = ChronoUnit.DAYS.between(range.from(), range.to()) + 1;
    LocalDate previousTo = range.from().minusDays(1);
    LocalDate previousFrom = previousTo.minusDays(days - 1);
    return Optional.of(new ComparisonRange(previousFrom, previousTo));
  }
}
