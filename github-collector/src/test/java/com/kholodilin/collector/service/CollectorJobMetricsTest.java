package com.kholodilin.collector.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CollectorJobMetricsTest {
  @Test
  void countersAreIndependentAndPerInstance() {
    var first = new CollectorJobMetrics();
    var second = new CollectorJobMetrics();

    first.recordAccepted();
    first.recordAccepted();
    first.recordRejectedCapacity();

    assertThat(first.accepted()).isEqualTo(2);
    assertThat(first.rejectedCapacity()).isEqualTo(1);
    assertThat(second.accepted()).isZero();
    assertThat(second.rejectedCapacity()).isZero();
  }
}
