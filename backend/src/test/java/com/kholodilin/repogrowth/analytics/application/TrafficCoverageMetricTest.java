package com.kholodilin.repogrowth.analytics.application;

import com.kholodilin.repogrowth.analytics.api.DashboardResponse.TrafficCoverage;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.assertThat;

class TrafficCoverageMetricTest {
    private final DashboardPeriod period = DashboardPeriod.of("7d", LocalDate.of(2026, 11, 3), null);
    @Test void completeZeroIsARealDeclineButMissingDataIsUnavailable() {
        var full = new TrafficCoverage(6, 6);
        var zero = AnalyticsService.metric(0, 10, period, full, full);
        assertThat(zero.value()).isZero();
        assertThat(zero.growthPercent()).isEqualTo(-100.0);
        var missing = AnalyticsService.metric(0, 10, period, new TrafficCoverage(0, 6), full);
        assertThat(missing.value()).isNull();
        assertThat(missing.growthPercent()).isNull();
    }
    @Test void partialTotalsStayUsefulButEitherIncompleteWindowSuppressesComparison() {
        var full = new TrafficCoverage(6, 6);
        var partial = new TrafficCoverage(3, 6);
        var current = AnalyticsService.metric(40, 50, period, partial, full);
        assertThat(current.value()).isEqualTo(40);
        assertThat(current.coverage()).isEqualTo(partial);
        assertThat(current.growthPercent()).isNull();
        assertThat(AnalyticsService.metric(40, 50, period, full, partial).growthPercent()).isNull();
        assertThat(AnalyticsService.metric(40, 50, period, full, null).growthPercent()).isNull();
        assertThat(AnalyticsService.metric(40, 0, period, full, full).growthPercent()).isNull();
        assertThat(AnalyticsService.metric(40, 50, period, full, new TrafficCoverage(7, 7)).growthPercent()).isNull();
    }
    @Test void allTimeAndEmptyExpectedCoverageNeverInventComparisons() {
        var full = new TrafficCoverage(1, 1);
        assertThat(new TrafficCoverage(0, 0).complete()).isFalse();
        assertThat(AnalyticsService.metric(4, 2, DashboardPeriod.of("all", LocalDate.now(), null), full, full).growthPercent()).isNull();
        assertThat(AnalyticsService.metric(4, 2, period, full, full).growthPercent()).isEqualTo(100.0);
    }
}
