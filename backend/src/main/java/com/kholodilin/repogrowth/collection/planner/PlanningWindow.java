package com.kholodilin.repogrowth.collection.planner;

import com.kholodilin.repogrowth.common.config.AppProperties;
import com.kholodilin.repogrowth.common.config.CollectionProperties;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

@Component
public class PlanningWindow {

    private final CollectionProperties collectionProperties;
    private final Clock clock;
    private final ZoneId zoneId;

    public PlanningWindow(CollectionProperties collectionProperties, Clock clock, AppProperties appProperties) {
        this.collectionProperties = collectionProperties;
        this.clock = clock;
        this.zoneId = ZoneId.of(appProperties.timezone());
    }

    public boolean isOpen() {
        LocalTime now = localTime();
        LocalTime from = collectionProperties.planner().from();
        LocalTime to = collectionProperties.planner().to();
        return !now.isBefore(from) && now.isBefore(to);
    }

    public LocalTime localTime() {
        return LocalTime.now(clock);
    }

    public LocalDate businessDate() {
        return LocalDate.now(clock);
    }

    public ZoneId zoneId() {
        return zoneId;
    }

    public LocalTime from() {
        return collectionProperties.planner().from();
    }

    public LocalTime to() {
        return collectionProperties.planner().to();
    }
}
