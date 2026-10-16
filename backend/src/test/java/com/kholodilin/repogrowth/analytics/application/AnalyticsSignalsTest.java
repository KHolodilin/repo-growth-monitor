package com.kholodilin.repogrowth.analytics.application;

import com.kholodilin.repogrowth.repository.persistence.RepositoryJdbcRepository.RepositorySignals;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnalyticsSignalsTest {

    @Test
    void readsMissingAndPresentAccessFlags() {
        assertThat(AnalyticsService.accessible(null)).isTrue();
        assertThat(AnalyticsService.accessible(new RepositorySignals(false, true))).isFalse();
        assertThat(AnalyticsService.accessible(new RepositorySignals(true, false))).isTrue();
        assertThat(AnalyticsService.trafficMissing(null)).isFalse();
        assertThat(AnalyticsService.trafficMissing(new RepositorySignals(true, true))).isTrue();
        assertThat(AnalyticsService.trafficMissing(new RepositorySignals(true, false))).isFalse();
    }
}
