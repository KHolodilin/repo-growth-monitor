package com.kholodilin.collector.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.kholodilin.collector.contracts.CollectorProtocol.JobState;
import com.kholodilin.collector.model.LocalJobState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class JobStateMapperTest {
  @Test
  void readyIsTheQueuedFormOfWireAccepted() {
    assertThat(JobStateMapper.toWire(LocalJobState.READY)).isEqualTo(JobState.ACCEPTED);
  }

  @ParameterizedTest
  @CsvSource({
    "RUNNING,RUNNING",
    "RETRY,RETRY",
    "SUCCEEDED,SUCCEEDED",
    "FAILED,FAILED",
    "CANCELLED,CANCELLED"
  })
  void otherStatesKeepTheirName(LocalJobState local, JobState wire) {
    assertThat(JobStateMapper.toWire(local)).isEqualTo(wire);
  }

  @Test
  void everyLocalStateHasAWireForm() {
    for (LocalJobState state : LocalJobState.values()) {
      assertThat(JobStateMapper.toWire(state)).isNotNull();
    }
  }
}
