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
    public record Planner(LocalTime from, LocalTime to, Duration interval) {
    }
}
