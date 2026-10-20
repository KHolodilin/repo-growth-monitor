package com.kholodilin.repogrowth.collection.planner;

import com.kholodilin.repogrowth.collection.persistence.PlannerSlotJdbcRepository;
import com.kholodilin.repogrowth.common.api.ApiException;
import com.kholodilin.repogrowth.common.config.CollectionProperties;
import com.kholodilin.repogrowth.search.planner.SearchPlanner;
import com.kholodilin.repogrowth.topic.planner.TopicPlanner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;

@Slf4j
@Component
public class DailyPlanner {

    private final PlanningWindow planningWindow;
    private final CollectionPlanner collectionPlanner;
    private final SearchPlanner searchPlanner;
    private final TopicPlanner topicPlanner;
    private final CollectionProperties collectionProperties;
    private final SnapshotSchedule snapshotSchedule;
    private final PlannerSlotJdbcRepository slotRepository;

    public DailyPlanner(
            PlanningWindow planningWindow,
            CollectionPlanner collectionPlanner,
            SearchPlanner searchPlanner,
            TopicPlanner topicPlanner,
            CollectionProperties collectionProperties,
            SnapshotSchedule snapshotSchedule,
            PlannerSlotJdbcRepository slotRepository
    ) {
        this.planningWindow = planningWindow;
        this.collectionPlanner = collectionPlanner;
        this.searchPlanner = searchPlanner;
        this.topicPlanner = topicPlanner;
        this.collectionProperties = collectionProperties;
        this.snapshotSchedule = snapshotSchedule;
        this.slotRepository = slotRepository;
    }

    @Scheduled(fixedDelayString = "${collection.planner.interval}")
    public void tick() {
        if (!collectionProperties.planner().enabled()) {
            log.debug("Planner skipped, scheduling disabled");
            return;
        }
        if (!planningWindow.isOpen()) {
            log.debug("Planner skipped, outside strict window");
            return;
        }
        planDue();
    }

    public PlannerResult planNow(boolean requireWindow) {
        if (requireWindow && !planningWindow.isOpen()) {
            throw ApiException.validation("Collection planner is outside the configured strict window");
        }
        return planDue();
    }

    private PlannerResult planDue() {
        LocalDate date = planningWindow.businessDate();
        Optional<LocalTime> slot = pendingSnapshot(date);
        boolean snapshotDue = slot.isPresent();
        int collection = collectionPlanner.planAll(date, snapshotDue);
        int search = searchPlanner.planAll(date, snapshotDue);
        int topics = topicPlanner.planAll(date, snapshotDue);
        if (snapshotDue) {
            slotRepository.save(date, slot.get());
            log.info("Planner snapshot slot={} businessDate={}", slot.get(), date);
        }
        return new PlannerResult(true, collection, search, topics, date.toString());
    }

    private Optional<LocalTime> pendingSnapshot(LocalDate date) {
        CollectionProperties.Planner planner = collectionProperties.planner();
        Optional<LocalTime> due = snapshotSchedule.due(
                planningWindow.localTime(),
                planningWindow.from(),
                planningWindow.to(),
                planner.snapshotInterval()
        );
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
            String businessDate
    ) {
    }
}
