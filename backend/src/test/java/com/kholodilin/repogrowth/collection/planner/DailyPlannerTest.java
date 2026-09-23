package com.kholodilin.repogrowth.collection.planner;

import com.kholodilin.repogrowth.common.api.ApiException;
import com.kholodilin.repogrowth.search.planner.SearchPlanner;
import com.kholodilin.repogrowth.topic.planner.TopicPlanner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    private DailyPlanner planner;
    private LocalDate date;

    @BeforeEach
    void setUp() {
        planner = new DailyPlanner(planningWindow, collectionPlanner, searchPlanner, topicPlanner);
        date = LocalDate.of(2026, 9, 23);
    }

    @Test
    void tickSkipsWhenTheWindowIsClosed() {
        when(planningWindow.isOpen()).thenReturn(false);
        planner.tick();
        verify(collectionPlanner, never()).planAll(date);
    }

    @Test
    void tickPlansCollectionSearchAndTopicsWhenOpen() {
        when(planningWindow.isOpen()).thenReturn(true);
        when(planningWindow.businessDate()).thenReturn(date);
        planner.tick();
        verify(collectionPlanner).planAll(date);
        verify(searchPlanner).planAll(date);
        verify(topicPlanner).planAll(date);
    }

    @Test
    void planNowRejectsAClosedWindowWhenRequired() {
        when(planningWindow.isOpen()).thenReturn(false);
        assertThatThrownBy(() -> planner.planNow(true)).isInstanceOf(ApiException.class);
    }

    @Test
    void planNowRunsAllPlanners() {
        when(planningWindow.businessDate()).thenReturn(date);
        when(collectionPlanner.planAll(date)).thenReturn(2);
        when(searchPlanner.planAll(date)).thenReturn(3);
        when(topicPlanner.planAll(date)).thenReturn(4);

        DailyPlanner.PlannerResult result = planner.planNow(false);

        assertThat(result.planned()).isTrue();
        assertThat(result.collectionRepositories()).isEqualTo(2);
        assertThat(result.searchQueries()).isEqualTo(3);
        assertThat(result.topicWatches()).isEqualTo(4);
        assertThat(result.businessDate()).isEqualTo("2026-09-23");
    }
}
