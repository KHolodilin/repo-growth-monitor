package com.kholodilin.collector.model;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/** Shared exponential backoff with optional symmetric jitter. */
public final class RetryBackoff {
  private RetryBackoff() {}

  /**
   * @param attempt zero-based failure/generation count used as the exponent
   * @param jitter fraction of the capped delay to randomize (0..1); 0.2 → ±20%
   */
  public static Duration delay(
      int attempt, Duration initial, Duration max, double multiplier, double jitter) {
    Objects.requireNonNull(initial);
    Objects.requireNonNull(max);
    if (attempt < 0 || multiplier < 1 || jitter < 0 || jitter > 1)
      throw new IllegalArgumentException("Invalid retry backoff");
    double base = initial.toMillis() * Math.pow(multiplier, attempt);
    long capped = Math.min(max.toMillis(), Math.max(1L, (long) base));
    if (jitter == 0) return Duration.ofMillis(capped);
    double factor = 1 + (ThreadLocalRandom.current().nextDouble() * 2 - 1) * jitter;
    return Duration.ofMillis(Math.max(1L, (long) (capped * factor)));
  }
}
