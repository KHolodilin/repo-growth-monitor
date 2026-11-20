package com.kholodilin.collector.support;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RetryBackoffTest {
  @Test
  void exponentialCapsAndAllowsZeroJitter() {
    assertThat(
            RetryBackoff.delay(0, Duration.ofSeconds(5), Duration.ofMinutes(5), 2.0, 0).toSeconds())
        .isEqualTo(5);
    assertThat(
            RetryBackoff.delay(3, Duration.ofSeconds(5), Duration.ofMinutes(5), 2.0, 0).toSeconds())
        .isEqualTo(40);
    assertThat(
            RetryBackoff.delay(10, Duration.ofSeconds(5), Duration.ofMinutes(5), 2.0, 0)
                .toMinutes())
        .isEqualTo(5);
  }

  @Test
  void jitterStaysNearBase() {
    Duration base = RetryBackoff.delay(1, Duration.ofSeconds(10), Duration.ofMinutes(5), 2.0, 0);
    for (int i = 0; i < 20; i++) {
      long ms =
          RetryBackoff.delay(1, Duration.ofSeconds(10), Duration.ofMinutes(5), 2.0, 0.2).toMillis();
      assertThat(ms).isBetween((long) (base.toMillis() * 0.8), (long) (base.toMillis() * 1.2));
    }
  }
}
