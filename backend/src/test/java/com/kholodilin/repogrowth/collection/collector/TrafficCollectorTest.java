package com.kholodilin.repogrowth.collection.collector;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TrafficCollectorTest {

    @Test
    void fillOmittedDaysWritesZerosBetweenMinAndMax() {
        Map<LocalDate, int[]> merged = new HashMap<>();
        merged.put(LocalDate.of(2026, 8, 26), new int[]{10, 4, 1, 1});
        merged.put(LocalDate.of(2026, 8, 28), new int[]{12, 5, 2, 1});

        TrafficCollector.fillOmittedDays(merged);

        assertThat(merged).containsOnlyKeys(
                LocalDate.of(2026, 8, 26),
                LocalDate.of(2026, 8, 27),
                LocalDate.of(2026, 8, 28)
        );
        assertThat(merged.get(LocalDate.of(2026, 8, 27))).containsExactly(0, 0, 0, 0);
        assertThat(merged.get(LocalDate.of(2026, 8, 26))).containsExactly(10, 4, 1, 1);
    }

    @Test
    void fillOmittedDaysDoesNothingWhenEmpty() {
        Map<LocalDate, int[]> merged = new HashMap<>();
        TrafficCollector.fillOmittedDays(merged);
        assertThat(merged).isEmpty();
    }
}
