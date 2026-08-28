package com.kholodilin.repogrowth.analytics.application;

import com.kholodilin.repogrowth.analytics.api.DashboardResponse.TrafficPoint;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardPartialDataTest {

    @Test
    void internalGapsAreReportedWithoutFillingZeros() {
        List<TrafficPoint> traffic = List.of(
                new TrafficPoint(LocalDate.of(2026, 8, 20), 100L, 40L, 5L),
                new TrafficPoint(LocalDate.of(2026, 8, 21), null, null, null),
                new TrafficPoint(LocalDate.of(2026, 8, 22), 130L, 50L, 8L)
        );
        var partial = AnalyticsService.partialData(traffic);
        assertThat(partial).isNotNull();
        assertThat(partial.present()).isTrue();
        assertThat(partial.message()).isEqualTo(
                "Data is unavailable for Aug 21 because the service was not running."
        );
    }

    @Test
    void leadingEmptyDaysBeforeFirstObservationAreNotPartial() {
        List<TrafficPoint> traffic = List.of(
                new TrafficPoint(LocalDate.of(2026, 8, 20), null, null, null),
                new TrafficPoint(LocalDate.of(2026, 8, 21), 10L, 4L, 1L),
                new TrafficPoint(LocalDate.of(2026, 8, 22), 12L, 5L, 1L)
        );
        assertThat(AnalyticsService.partialData(traffic)).isNull();
    }

    @Test
    void consecutiveGapsAreFormattedAsARange() {
        assertThat(AnalyticsService.formatGapRanges(List.of(
                LocalDate.of(2026, 8, 21),
                LocalDate.of(2026, 8, 22)
        ))).isEqualTo("Aug 21–22");
    }

    @Test
    void trailingUnpublishedDaysAreDroppedFromTheSeries() {
        List<TrafficPoint> traffic = List.of(
                new TrafficPoint(LocalDate.of(2026, 8, 26), 100L, 40L, 5L),
                new TrafficPoint(LocalDate.of(2026, 8, 27), null, null, null),
                new TrafficPoint(LocalDate.of(2026, 8, 28), null, null, null)
        );
        List<TrafficPoint> trimmed = AnalyticsService.dropTrailingGaps(traffic, TrafficPoint::views);
        assertThat(trimmed).extracting(TrafficPoint::date)
                .containsExactly(LocalDate.of(2026, 8, 26));
    }
}
