package com.kholodilin.repogrowth.event.detect;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReadmeSignificanceTest {

    @Test
    void ignoresWhitespaceOnlyChanges() {
        assertThat(ReadmeSignificance.significant("# Hello\n\nworld", "  # Hello world  ")).isFalse();
    }

    @Test
    void treatsFifteenPercentChangeAsSignificant() {
        String previous = "a".repeat(100);
        String current = "a".repeat(85) + "b".repeat(15);
        assertThat(ReadmeSignificance.significant(previous, current)).isTrue();
    }

    @Test
    void ignoresSmallShareBelowThreshold() {
        String previous = "a".repeat(100);
        String current = "a".repeat(90) + "b".repeat(10);
        assertThat(ReadmeSignificance.significant(previous, current)).isFalse();
    }

    @Test
    void treatsFourHundredCharacterDeltaAsSignificant() {
        String previous = "prefix " + "a".repeat(50) + " suffix";
        String current = "prefix " + "b".repeat(450) + " suffix";
        assertThat(ReadmeSignificance.significant(previous, current)).isTrue();
    }
}
