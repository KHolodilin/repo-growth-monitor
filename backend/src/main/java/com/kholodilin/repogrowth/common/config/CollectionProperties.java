package com.kholodilin.repogrowth.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.time.LocalTime;

@ConfigurationProperties(prefix = "collection")
public record CollectionProperties(
        int workers,
        Duration jobLease,
        Planner planner
) {
    public record Planner(
            boolean enabled,
            LocalTime from,
            LocalTime to,
            Duration interval,
            Duration snapshotInterval
    ) {
        public Planner {
            if (snapshotInterval == null || snapshotInterval.compareTo(Duration.ofMinutes(1)) < 0) {
                throw new IllegalArgumentException("collection.planner.snapshot-interval must be at least 1 minute");
            }
        }
    }
}
