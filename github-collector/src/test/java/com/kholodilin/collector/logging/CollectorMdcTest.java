package com.kholodilin.collector.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class CollectorMdcTest {
  @AfterEach
  void clear() {
    CollectorMdc.clear();
  }

  @Test
  void bindsAttemptJobAndTaskUnderStableKeys() {
    CollectorMdc.bindAttempt("attempt-1");
    CollectorMdc.bindJob("job-1", "STATS");

    assertThat(MDC.get("attemptId")).isEqualTo("attempt-1");
    assertThat(MDC.get("jobId")).isEqualTo("job-1");
    assertThat(MDC.get("taskType")).isEqualTo("STATS");
  }

  @Test
  void nullAndBlankValuesAreIgnored() {
    CollectorMdc.bindAttempt(null);
    CollectorMdc.bindAttempt("  ");
    CollectorMdc.bindJob(null, null);

    assertThat(MDC.get("attemptId")).isNull();
    assertThat(MDC.get("jobId")).isNull();
    assertThat(MDC.get("taskType")).isNull();
  }

  @Test
  void clearRemovesOnlyCollectorKeys() {
    MDC.put("unrelated", "keep");
    CollectorMdc.bindAttempt("attempt-1");
    CollectorMdc.bindJob("job-1", "STATS");

    CollectorMdc.clear();

    assertThat(MDC.get("attemptId")).isNull();
    assertThat(MDC.get("jobId")).isNull();
    assertThat(MDC.get("taskType")).isNull();
    assertThat(MDC.get("unrelated")).isEqualTo("keep");
    MDC.remove("unrelated");
  }
}
