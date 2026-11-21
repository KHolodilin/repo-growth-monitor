package com.kholodilin.repogrowth.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PlannerSchedulingTest {

  @Test
  void schedulingConfigurationCanBeConstructed() {
    assertThat(new PlannerScheduling()).isNotNull();
  }
}
