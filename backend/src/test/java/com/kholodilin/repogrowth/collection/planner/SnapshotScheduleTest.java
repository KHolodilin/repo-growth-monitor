package com.kholodilin.repogrowth.collection.planner;

import com.kholodilin.repogrowth.common.config.CollectionProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SnapshotScheduleTest {

    private final SnapshotSchedule schedule = new SnapshotSchedule();

    @Test
    void slotsEveryThreeHoursUntilTheWindowCloses() {
        assertThat(schedule.slots(LocalTime.of(6, 0), LocalTime.of(18, 0), Duration.ofHours(3)))
                .containsExactly(
                        LocalTime.of(6, 0),
                        LocalTime.of(9, 0),
                        LocalTime.of(12, 0),
                        LocalTime.of(15, 0)
                );
    }

    @Test
    void slotsFollowTheConfiguredStep() {
        assertThat(schedule.slots(LocalTime.of(6, 0), LocalTime.of(18, 0), Duration.ofHours(6)))
                .containsExactly(LocalTime.of(6, 0), LocalTime.of(12, 0));
        assertThat(schedule.slots(LocalTime.of(6, 0), LocalTime.of(18, 0), Duration.ofHours(4)))
                .containsExactly(LocalTime.of(6, 0), LocalTime.of(10, 0), LocalTime.of(14, 0));
    }

    @Test
    void dueSlotIsTheLatestOneThatHasStarted() {
        Duration step = Duration.ofHours(3);
        LocalTime from = LocalTime.of(6, 0);
        LocalTime to = LocalTime.of(18, 0);
        assertThat(schedule.due(LocalTime.of(5, 59), from, to, step)).isEmpty();
        assertThat(schedule.due(LocalTime.of(6, 0), from, to, step)).contains(LocalTime.of(6, 0));
        assertThat(schedule.due(LocalTime.of(7, 0), from, to, step)).contains(LocalTime.of(6, 0));
        assertThat(schedule.due(LocalTime.of(9, 5), from, to, step)).contains(LocalTime.of(9, 0));
        assertThat(schedule.due(LocalTime.of(17, 59), from, to, step)).contains(LocalTime.of(15, 0));
        assertThat(schedule.due(LocalTime.of(18, 0), from, to, step)).isEmpty();
        assertThat(schedule.due(null, from, to, step)).isEmpty();
    }

    @Test
    void emptyWhenTheStepOrWindowCannotProduceASlot() {
        assertThat(schedule.slots(LocalTime.of(6, 0), LocalTime.of(6, 0), Duration.ofHours(3))).isEmpty();
        assertThat(schedule.slots(LocalTime.of(6, 0), LocalTime.of(18, 0), Duration.ZERO)).isEmpty();
        assertThat(schedule.slots(LocalTime.of(6, 0), LocalTime.of(18, 0), Duration.ofHours(-1))).isEmpty();
        assertThat(schedule.slots(LocalTime.of(6, 0), LocalTime.of(18, 0), null)).isEmpty();
        assertThat(schedule.slots(null, LocalTime.of(18, 0), Duration.ofHours(3))).isEmpty();
        assertThat(schedule.slots(LocalTime.of(22, 0), LocalTime.of(23, 0), Duration.ofHours(3)))
                .containsExactly(LocalTime.of(22, 0));
    }

    @Test
    void dueIsEmptyWhenNoSlotHasStartedInsideTheWindow() {
        assertThat(schedule.due(
                LocalTime.of(6, 30),
                LocalTime.of(6, 0),
                LocalTime.of(18, 0),
                Duration.ZERO
        )).isEmpty();
    }

    @Test
    void snapshotIntervalMustBeAtLeastOneMinute() {
        assertThatThrownBy(() -> planner(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> planner(Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> planner(Duration.ofSeconds(30))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> planner(Duration.ofHours(-1))).isInstanceOf(IllegalArgumentException.class);
        assertThat(planner(Duration.ofMinutes(1)).snapshotInterval()).isEqualTo(Duration.ofMinutes(1));
    }

    private static CollectionProperties.Planner planner(Duration snapshotInterval) {
        return new CollectionProperties.Planner(
                true,
                LocalTime.of(6, 0),
                LocalTime.of(18, 0),
                Duration.ofMinutes(10),
                snapshotInterval
        );
    }
}
