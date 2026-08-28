package com.kholodilin.repogrowth.analytics.application;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardPeriodTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 28);

    @Test
    void thirtyDaysUsesInclusiveWindowAndEqualPreviousPeriod() {
        DashboardPeriod period = DashboardPeriod.of("30d", TODAY, null);
        assertThat(period.value()).isEqualTo("30d");
        assertThat(period.from()).isEqualTo(LocalDate.of(2026, 7, 30));
        assertThat(period.to()).isEqualTo(TODAY);
        assertThat(period.previousFrom()).isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(period.previousTo()).isEqualTo(LocalDate.of(2026, 7, 29));
        assertThat(period.allTime()).isFalse();
    }

    @Test
    void oneDayWindow() {
        DashboardPeriod period = DashboardPeriod.of("1d", TODAY, null);
        assertThat(period.value()).isEqualTo("1d");
        assertThat(period.from()).isEqualTo(TODAY);
        assertThat(period.to()).isEqualTo(TODAY);
        assertThat(period.previousFrom()).isEqualTo(LocalDate.of(2026, 8, 27));
        assertThat(period.previousTo()).isEqualTo(LocalDate.of(2026, 8, 27));
    }

    @Test
    void sevenDaysWindow() {
        DashboardPeriod period = DashboardPeriod.of("7d", TODAY, null);
        assertThat(period.from()).isEqualTo(LocalDate.of(2026, 8, 22));
        assertThat(period.previousFrom()).isEqualTo(LocalDate.of(2026, 8, 15));
        assertThat(period.previousTo()).isEqualTo(LocalDate.of(2026, 8, 21));
    }

    @Test
    void allTimeHasNoPreviousWindow() {
        DashboardPeriod period = DashboardPeriod.of("all", TODAY, LocalDate.of(2026, 1, 1));
        assertThat(period.from()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(period.to()).isEqualTo(TODAY);
        assertThat(period.previousFrom()).isNull();
        assertThat(period.previousTo()).isNull();
        assertThat(period.allTime()).isTrue();
    }

    @Test
    void unknownPeriodDefaultsToThirtyDays() {
        assertThat(DashboardPeriod.of("weird", TODAY, null).value()).isEqualTo("30d");
        assertThat(DashboardPeriod.of(null, TODAY, null).value()).isEqualTo("30d");
    }
}
