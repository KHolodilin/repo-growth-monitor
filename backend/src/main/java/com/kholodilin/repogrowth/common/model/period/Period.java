package com.kholodilin.repogrowth.common.model.period;

import java.util.Locale;
import java.util.Optional;

/** Wire period values shared by resource APIs. Default product period is {@link #D7}. */
public enum Period {
  D1("1d"),
  D7("7d"),
  D30("30d"),
  D90("90d"),
  Y1("1y"),
  ALL("all");

  public static final Period DEFAULT = D7;

  private final String wire;

  Period(String wire) {
    this.wire = wire;
  }

  public String wire() {
    return wire;
  }

  public boolean allTime() {
    return this == ALL;
  }

  public static Optional<Period> tryParse(String raw) {
    if (raw == null || raw.isBlank()) {
      return Optional.of(DEFAULT);
    }
    String normalized = raw.trim().toLowerCase(Locale.ROOT);
    for (Period period : values()) {
      if (period.wire.equals(normalized)) {
        return Optional.of(period);
      }
    }
    return Optional.empty();
  }
}
