package com.kholodilin.repogrowth.traffic.model;

import java.time.LocalDate;

/** Per-repository traffic totals for one calendar day (portfolio series). */
public record RepositoryDayTotals(
    LocalDate date,
    String fullName,
    long views,
    long uniqueVisitors,
    long clones,
    long uniqueCloners) {

  public RepositoryDayTotals(
      LocalDate date, String fullName, long views, long uniqueVisitors, long clones) {
    this(date, fullName, views, uniqueVisitors, clones, 0);
  }
}
