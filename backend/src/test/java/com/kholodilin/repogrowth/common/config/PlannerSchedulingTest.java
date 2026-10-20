package com.kholodilin.repogrowth.common.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlannerSchedulingTest {

    @Test
    void schedulingConfigurationCanBeConstructed() {
        assertThat(new PlannerScheduling()).isNotNull();
    }
}
