package com.kholodilin.repogrowth.collection.planner;

import com.kholodilin.repogrowth.common.api.ApiException;
import com.kholodilin.repogrowth.search.planner.SearchPlanner;
import com.kholodilin.repogrowth.topic.planner.TopicPlanner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class DailyPlanner {

    private final PlanningWindow planningWindow;
    private final CollectionPlanner collectionPlanner;
    private final SearchPlanner searchPlanner;
    private final TopicPlanner topicPlanner;

    public DailyPlanner(
            PlanningWindow planningWindow,
            CollectionPlanner collectionPlanner,
            SearchPlanner searchPlanner,
            TopicPlanner topicPlanner
    ) {
        this.planningWindow = planningWindow;
        this.collectionPlanner = collectionPlanner;
        this.searchPlanner = searchPlanner;
        this.topicPlanner = topicPlanner;
    }

    @Scheduled(fixedDelayString = "${collection.planner.interval}")
    public void tick() {
        if (!planningWindow.isOpen()) {
            log.debug("Planner skipped, outside strict window");
            return;
        }
        collectionPlanner.planAll(planningWindow.businessDate());
        searchPlanner.planAll(planningWindow.businessDate());
        topicPlanner.planAll(planningWindow.businessDate());
    }

    public PlannerResult planNow(boolean requireWindow) {
        if (requireWindow && !planningWindow.isOpen()) {
            throw ApiException.validation("Collection planner is outside the configured strict window");
        }
        int collection = collectionPlanner.planAll(planningWindow.businessDate());
        int search = searchPlanner.planAll(planningWindow.businessDate());
        int topics = topicPlanner.planAll(planningWindow.businessDate());
        return new PlannerResult(true, collection, search, topics, planningWindow.businessDate().toString());
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
