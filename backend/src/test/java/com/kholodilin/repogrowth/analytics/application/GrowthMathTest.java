package com.kholodilin.repogrowth.analytics.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GrowthMathTest {

    @Test
    void percentUsesPreviousPeriodAsBaseline() {
        assertThat(GrowthMath.percent(4080, 3450)).isEqualTo(18.3);
    }

    @Test
    void zeroPreviousAndZeroCurrentIsNeutral() {
        assertThat(GrowthMath.percent(0, 0)).isEqualTo(0.0);
    }

    @Test
    void zeroPreviousWithGrowthIsUndefined() {
        assertThat(GrowthMath.percent(10, 0)).isNull();
    }

    @Test
    void negativeGrowth() {
        assertThat(GrowthMath.percent(93, 100)).isEqualTo(-7.0);
    }
}
