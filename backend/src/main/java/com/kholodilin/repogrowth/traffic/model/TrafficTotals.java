package com.kholodilin.repogrowth.traffic.model;

/** Aggregated traffic counts for a repository or portfolio window. */
public record TrafficTotals(long views, long uniqueVisitors, long clones, long uniqueCloners) {}
