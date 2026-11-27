package com.kholodilin.repogrowth.common.api;

import com.kholodilin.repogrowth.common.model.period.Period;

/** Parses period wire values for resource APIs. Invalid values are validation errors. */
public final class PeriodParser {

  private PeriodParser() {}

  public static Period parse(String raw) {
    return Period.tryParse(raw)
        .orElseThrow(() -> ApiException.validation("Unsupported period: " + raw.trim()));
  }
}
