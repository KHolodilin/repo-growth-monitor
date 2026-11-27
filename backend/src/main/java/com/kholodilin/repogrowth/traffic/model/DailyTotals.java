package com.kholodilin.repogrowth.traffic.model;

import java.time.LocalDate;

/** Portfolio-wide traffic totals for one calendar day. */
public record DailyTotals(LocalDate date, long views, long uniqueVisitors, long clones) {}
