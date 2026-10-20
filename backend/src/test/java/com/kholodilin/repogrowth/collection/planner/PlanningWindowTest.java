package com.kholodilin.repogrowth.collection.planner;

import com.kholodilin.repogrowth.common.config.AppProperties;
import com.kholodilin.repogrowth.common.config.CollectionProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class PlanningWindowTest {

    @Test
    void openInsideWindowAndClosedAfter() {
        CollectionProperties properties = new CollectionProperties(
                4,
                Duration.ofMinutes(5),
                new CollectionProperties.Planner(true, LocalTime.of(10, 0), LocalTime.of(18, 0), Duration.ofMinutes(10), Duration.ofHours(3))
        );
        AppProperties app = new AppProperties("UTC");
        Clock inside = Clock.fixed(Instant.parse("2026-08-27T12:00:00Z"), ZoneOffset.UTC);
        assertThat(new PlanningWindow(properties, inside, app).isOpen()).isTrue();
        Clock after = Clock.fixed(Instant.parse("2026-08-27T21:00:00Z"), ZoneOffset.UTC);
        assertThat(new PlanningWindow(properties, after, app).isOpen()).isFalse();
        Clock atEnd = Clock.fixed(Instant.parse("2026-08-27T18:00:00Z"), ZoneOffset.UTC);
        assertThat(new PlanningWindow(properties, atEnd, app).isOpen()).isFalse();

        PlanningWindow window = new PlanningWindow(properties, inside, new AppProperties("Europe/Moscow"));
        assertThat(window.from()).isEqualTo(LocalTime.of(10, 0));
        assertThat(window.to()).isEqualTo(LocalTime.of(18, 0));
        assertThat(window.zoneId().getId()).isEqualTo("Europe/Moscow");
    }
}
