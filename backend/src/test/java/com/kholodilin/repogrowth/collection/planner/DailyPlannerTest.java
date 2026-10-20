package com.kholodilin.repogrowth.collection.planner;

import com.kholodilin.repogrowth.collection.persistence.PlannerSlotJdbcRepository;
import com.kholodilin.repogrowth.common.api.ApiException;
import com.kholodilin.repogrowth.common.config.CollectionProperties;
import com.kholodilin.repogrowth.search.planner.SearchPlanner;
import com.kholodilin.repogrowth.topic.planner.TopicPlanner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DailyPlannerTest {

    @Mock
    PlanningWindow planningWindow;
    @Mock
    CollectionPlanner collectionPlanner;
    @Mock
    SearchPlanner searchPlanner;
    @Mock
    TopicPlanner topicPlanner;
    @Mock
    PlannerSlotJdbcRepository slotRepository;

    private DailyPlanner planner;
    private LocalDate date;
    private CollectionProperties enabled;
    private CollectionProperties disabled;

    @BeforeEach
    void setUp() {
        date = LocalDate.of(2026, 10, 20);
        enabled = properties(true);
        disabled = properties(false);
        planner = new DailyPlanner(
                planningWindow,
                collectionPlanner,
                searchPlanner,
                topicPlanner,
                enabled,
                new SnapshotSchedule(),
                slotRepository
        );
    }

    @Test
    void tickSkipsWhenSchedulingIsDisabled() {
        planner = plannerWith(disabled);
        planner.tick();
        verify(planningWindow, never()).isOpen();
        verify(collectionPlanner, never()).planAll(any(), anyBoolean());
    }

    @Test
    void tickSkipsWhenTheWindowIsClosed() {
        when(planningWindow.isOpen()).thenReturn(false);
        planner.tick();
        verify(collectionPlanner, never()).planAll(any(), anyBoolean());
    }

    @Test
    void tickAppliesTheLatestDueSlotOnce() {
        openAt(LocalTime.of(7, 0));
        when(slotRepository.find(date)).thenReturn(Optional.empty());
        planner.tick();
        verify(collectionPlanner).planAll(date, true);
        verify(searchPlanner).planAll(date, true);
        verify(topicPlanner).planAll(date, true);
        verify(slotRepository).save(date, LocalTime.of(6, 0));
    }

    @Test
    void tickDoesNotRepeatASlotThatAlreadyRan() {
        openAt(LocalTime.of(9, 5));
        when(slotRepository.find(date)).thenReturn(Optional.of(LocalTime.of(9, 0)));
        planner.tick();
        verify(collectionPlanner).planAll(date, false);
        verify(searchPlanner).planAll(date, false);
        verify(topicPlanner).planAll(date, false);
        verify(slotRepository, never()).save(any(), any());
    }

    @Test
    void tickMovesOnWhenALaterSlotIsDue() {
        openAt(LocalTime.of(12, 0));
        when(slotRepository.find(date)).thenReturn(Optional.of(LocalTime.of(9, 0)));
        planner.tick();
        verify(collectionPlanner).planAll(date, true);
        verify(slotRepository).save(date, LocalTime.of(12, 0));
    }

    @Test
    void planNowRejectsAClosedWindowWhenRequired() {
        when(planningWindow.isOpen()).thenReturn(false);
        assertThatThrownBy(() -> planner.planNow(true)).isInstanceOf(ApiException.class);
        verify(collectionPlanner, never()).planAll(any(), anyBoolean());
    }

    @Test
    void planNowRunsAllPlannersForTheDueSlot() {
        when(planningWindow.businessDate()).thenReturn(date);
        when(planningWindow.localTime()).thenReturn(LocalTime.of(7, 0));
        when(planningWindow.from()).thenReturn(LocalTime.of(6, 0));
        when(planningWindow.to()).thenReturn(LocalTime.of(18, 0));
        when(slotRepository.find(date)).thenReturn(Optional.empty());
        when(collectionPlanner.planAll(date, true)).thenReturn(2);
        when(searchPlanner.planAll(date, true)).thenReturn(3);
        when(topicPlanner.planAll(date, true)).thenReturn(4);

        DailyPlanner.PlannerResult result = planner.planNow(false);

        assertThat(result.planned()).isTrue();
        assertThat(result.collectionRepositories()).isEqualTo(2);
        assertThat(result.searchQueries()).isEqualTo(3);
        assertThat(result.topicWatches()).isEqualTo(4);
        assertThat(result.businessDate()).isEqualTo("2026-10-20");
        verify(slotRepository).save(date, LocalTime.of(6, 0));
    }

    @Test
    void planNowOutsideTheWindowDoesNotOpenASnapshot() {
        when(planningWindow.businessDate()).thenReturn(date);
        when(planningWindow.localTime()).thenReturn(LocalTime.of(19, 0));
        when(planningWindow.from()).thenReturn(LocalTime.of(6, 0));
        when(planningWindow.to()).thenReturn(LocalTime.of(18, 0));
        when(collectionPlanner.planAll(date, false)).thenReturn(1);
        when(searchPlanner.planAll(date, false)).thenReturn(0);
        when(topicPlanner.planAll(date, false)).thenReturn(0);

        planner.planNow(false);

        verify(slotRepository, never()).find(any());
        verify(slotRepository, never()).save(any(), any());
    }

    private void openAt(LocalTime now) {
        when(planningWindow.isOpen()).thenReturn(true);
        when(planningWindow.businessDate()).thenReturn(date);
        when(planningWindow.localTime()).thenReturn(now);
        when(planningWindow.from()).thenReturn(LocalTime.of(6, 0));
        when(planningWindow.to()).thenReturn(LocalTime.of(18, 0));
    }

    private DailyPlanner plannerWith(CollectionProperties properties) {
        return new DailyPlanner(
                planningWindow,
                collectionPlanner,
                searchPlanner,
                topicPlanner,
                properties,
                new SnapshotSchedule(),
                slotRepository
        );
    }

    private static CollectionProperties properties(boolean schedulingEnabled) {
        return new CollectionProperties(
                1,
                Duration.ofMinutes(5),
                new CollectionProperties.Planner(
                        schedulingEnabled,
                        LocalTime.of(6, 0),
                        LocalTime.of(18, 0),
                        Duration.ofMinutes(10),
                        Duration.ofHours(3)
                )
        );
    }
}
