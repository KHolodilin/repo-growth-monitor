package com.kholodilin.repogrowth.collection.planner;

import com.kholodilin.repogrowth.collection.persistence.PlannerSlotJdbcRepository;
import com.kholodilin.repogrowth.common.api.ApiException;
import com.kholodilin.repogrowth.common.config.CollectionProperties;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class DailyPlanner {

  @Value("${rgm.planner.enabled:true}")
  private boolean plannerEnabled = true;

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private com.kholodilin.repogrowth.orchestration.CollectionRequestService requestService;

  private final PlanningWindow planningWindow;
  private final CollectionProperties collectionProperties;
  private final SnapshotSchedule snapshotSchedule;
  private final PlannerSlotJdbcRepository slotRepository;

  public DailyPlanner(
      PlanningWindow planningWindow,
      CollectionProperties collectionProperties,
      SnapshotSchedule snapshotSchedule,
      PlannerSlotJdbcRepository slotRepository) {
    this.planningWindow = planningWindow;
    this.collectionProperties = collectionProperties;
    this.snapshotSchedule = snapshotSchedule;
    this.slotRepository = slotRepository;
  }

  @Scheduled(fixedDelayString = "${collection.planner.interval}")
  public void tick() {
    if (!plannerEnabled || !collectionProperties.planner().enabled()) {
      log.debug("Planner skipped, scheduling disabled");
      return;
    }
    if (requestService == null) {
      log.debug("Planner skipped, collector orchestration is disabled");
      return;
    }
    if (!planningWindow.isOpen()) {
      log.debug("Planner skipped, outside strict window");
      return;
    }
    planDue();
  }

  public PlannerResult planNow(boolean requireWindow) {
    if (!plannerEnabled)
      throw ApiException.validation("Collection planning is disabled in this role");
    if (requestService == null) {
      throw ApiException.serviceUnavailable("Collector orchestration is required");
    }
    if (requireWindow && !planningWindow.isOpen()) {
      throw ApiException.validation("Collection planner is outside the configured strict window");
    }
    return planDue();
  }

  private PlannerResult planDue() {
    LocalDate date = planningWindow.businessDate();
    Optional<LocalTime> slot = pendingSnapshot(date);
    var counts = requestService.scheduled(date, slot.map(LocalTime::toString).orElse(null));
    if (slot.isPresent()) {
      slotRepository.save(date, slot.get());
      log.info("Planner snapshot slot={} businessDate={}", slot.get(), date);
    }
    return new PlannerResult(
        true, counts.repositories(), counts.queries(), counts.watches(), date.toString());
  }

  private Optional<LocalTime> pendingSnapshot(LocalDate date) {
    CollectionProperties.Planner planner = collectionProperties.planner();
    Optional<LocalTime> due =
        snapshotSchedule.due(
            planningWindow.localTime(),
            planningWindow.from(),
            planningWindow.to(),
            planner.snapshotInterval());
    if (due.isEmpty()) {
      return Optional.empty();
    }
    Optional<LocalTime> applied = slotRepository.find(date);
    if (applied.isPresent() && !due.get().isAfter(applied.get())) {
      return Optional.empty();
    }
    return due;
  }

  public record PlannerResult(
      boolean planned,
      int collectionRepositories,
      int searchQueries,
      int topicWatches,
      String businessDate) {}
}
