package com.kholodilin.collector.integration.github;

import static org.assertj.core.api.Assertions.*;

import com.kholodilin.collector.model.PacingSettings;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class LocalGitHubPacerTest {
  @Test
  void limitsBurstAndConcurrencyAndPermitCloseIsIdempotent() {
    var gate =
        new LocalGitHubPacer(
            new PacingSettings(
                1, Duration.ofMillis(1200), Duration.ofMillis(3000), Duration.ofMillis(1200)));
    var first = gate.tryAcquire("principal", "core").orElseThrow();
    assertThat(gate.tryAcquire("other", "search")).isEmpty();
    first.close();
    first.close();
    assertThat(gate.tryAcquire("principal", "core")).isEmpty();
    var other = gate.tryAcquire("other", "search").orElseThrow();
    assertThat(gate.tryAcquire("third", "core")).isEmpty();
    other.close();
  }
}
