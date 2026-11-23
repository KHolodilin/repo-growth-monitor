package com.kholodilin.repogrowth.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class LogMdcTest {
  @AfterEach
  void clear() {
    MDC.clear();
  }

  @Test
  void putsAndClearsJobContext() {
    LogMdc.repositoryId(1L);
    LogMdc.collectionRunId(2L);
    LogMdc.collectionJobId(3L);
    LogMdc.searchRunId(4L);
    assertThat(MDC.get("repositoryId")).isEqualTo("1");
    assertThat(MDC.get("collectionRunId")).isEqualTo("2");
    assertThat(MDC.get("collectionJobId")).isEqualTo("3");
    assertThat(MDC.get("searchRunId")).isEqualTo("4");
    LogMdc.repositoryId(null);
    assertThat(MDC.get("repositoryId")).isNull();
    LogMdc.clearJob();
    assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
  }
}
