package com.kholodilin.repogrowth.collection.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kholodilin.repogrowth.collection.planner.PlanningWindow;
import com.kholodilin.repogrowth.collection.planner.SnapshotSchedule;
import com.kholodilin.repogrowth.collection.repository.PlannerSlotJdbcRepository;
import com.kholodilin.repogrowth.common.api.ApiException;
import com.kholodilin.repogrowth.common.config.CollectionProperties;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class DailyPlannerTest {

  @Mock PlanningWindow planningWindow;
  @Mock CollectionRequestService requestService;
  @Mock PlannerSlotJdbcRepository slotRepository;

  private DailyPlanner planner;
  private LocalDate date;
  private CollectionProperties enabled;
  private CollectionProperties disabled;

  @BeforeEach
  void setUp() {
    date = LocalDate.of(2026, 10, 20);
    enabled = properties(true);
    disabled = properties(false);
    planner = plannerWith(enabled);
    ReflectionTestUtils.setField(planner, "requestService", requestService);
  }

  @Test
  void tickSkipsWhenSchedulingIsDisabled() {
    planner = plannerWith(disabled);
    ReflectionTestUtils.setField(planner, "requestService", requestService);
    planner.tick();
    verify(planningWindow, never()).isOpen();
    verify(requestService, never()).scheduled(any(), any());
  }

  @Test
  void tickSkipsWhenCollectorOrchestrationIsMissing() {
    planner = plannerWith(enabled);
    planner.tick();
    verify(planningWindow, never()).isOpen();
    verify(slotRepository, never()).save(any(), any());
  }

  @Test
  void tickSkipsWhenTheWindowIsClosed() {
    when(planningWindow.isOpen()).thenReturn(false);
    planner.tick();
    verify(requestService, never()).scheduled(any(), any());
  }

  @Test
  void tickAppliesTheLatestDueSlotOnce() {
    openAt(LocalTime.of(7, 0));
    when(slotRepository.find(date)).thenReturn(Optional.empty());
    when(requestService.scheduled(date, "06:00"))
        .thenReturn(new CollectionRequestService.ScheduledCounts(1, 0, 0));
    planner.tick();
    verify(requestService).scheduled(date, "06:00");
    verify(slotRepository).save(date, LocalTime.of(6, 0));
  }

  @Test
  void tickDoesNotRepeatASlotThatAlreadyRan() {
    openAt(LocalTime.of(9, 5));
    when(slotRepository.find(date)).thenReturn(Optional.of(LocalTime.of(9, 0)));
    when(requestService.scheduled(date, null))
        .thenReturn(new CollectionRequestService.ScheduledCounts(1, 0, 0));
    planner.tick();
    verify(requestService).scheduled(date, null);
    verify(slotRepository, never()).save(any(), any());
  }

  @Test
  void tickMovesOnWhenALaterSlotIsDue() {
    openAt(LocalTime.of(12, 0));
    when(slotRepository.find(date)).thenReturn(Optional.of(LocalTime.of(9, 0)));
    when(requestService.scheduled(date, "12:00"))
        .thenReturn(new CollectionRequestService.ScheduledCounts(1, 0, 0));
    planner.tick();
    verify(requestService).scheduled(date, "12:00");
    verify(slotRepository).save(date, LocalTime.of(12, 0));
  }

  @Test
  void planNowRejectsAClosedWindowWhenRequired() {
    when(planningWindow.isOpen()).thenReturn(false);
    assertThatThrownBy(() -> planner.planNow(true)).isInstanceOf(ApiException.class);
    verify(requestService, never()).scheduled(any(), any());
  }

  @Test
  void planNowRejectsAPlannerThatIsDisabledInThisRole() {
    ReflectionTestUtils.setField(planner, "plannerEnabled", false);
    assertThatThrownBy(() -> planner.planNow(false))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("disabled");
    verify(requestService, never()).scheduled(any(), any());
  }

  @Test
  void planNowRequiresCollectorOrchestration() {
    planner = plannerWith(enabled);
    assertThatThrownBy(() -> planner.planNow(false))
        .isInstanceOfSatisfying(
            ApiException.class,
            ex -> {
              assertThat(ex.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
              assertThat(ex.getMessage()).contains("Collector orchestration is required");
            });
    verify(slotRepository, never()).save(any(), any());
  }

  @Test
  void planNowReportsTheScheduledCountsForTheDueSlot() {
    when(planningWindow.businessDate()).thenReturn(date);
    when(planningWindow.localTime()).thenReturn(LocalTime.of(7, 0));
    when(planningWindow.from()).thenReturn(LocalTime.of(6, 0));
    when(planningWindow.to()).thenReturn(LocalTime.of(18, 0));
    when(slotRepository.find(date)).thenReturn(Optional.empty());
    when(requestService.scheduled(date, "06:00"))
        .thenReturn(new CollectionRequestService.ScheduledCounts(2, 3, 4));

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
    when(requestService.scheduled(date, null))
        .thenReturn(new CollectionRequestService.ScheduledCounts(1, 0, 0));

    planner.planNow(false);

    verify(requestService).scheduled(date, null);
    verify(slotRepository, never()).find(any());
    verify(slotRepository, never()).save(any(), any());
    verify(requestService, never()).scheduled(any(), anyString());
  }

  private void openAt(LocalTime now) {
    when(planningWindow.isOpen()).thenReturn(true);
    when(planningWindow.businessDate()).thenReturn(date);
    when(planningWindow.localTime()).thenReturn(now);
    when(planningWindow.from()).thenReturn(LocalTime.of(6, 0));
    when(planningWindow.to()).thenReturn(LocalTime.of(18, 0));
  }

  private DailyPlanner plannerWith(CollectionProperties properties) {
    return new DailyPlanner(planningWindow, properties, new SnapshotSchedule(), slotRepository);
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
            Duration.ofHours(3)));
  }
}
