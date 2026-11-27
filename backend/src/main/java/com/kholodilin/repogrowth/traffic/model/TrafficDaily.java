package com.kholodilin.repogrowth.traffic.model;

import java.time.LocalDate;

public record TrafficDaily(
    Long id,
    long repositoryId,
    LocalDate trafficDate,
    int views,
    int uniqueVisitors,
    int clones,
    int uniqueCloners) {}
