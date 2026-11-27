package com.kholodilin.repogrowth.traffic.model;

import java.time.LocalDate;

/** Presence of a stored traffic_daily row for a repository on a date. */
public record StoredTrafficDay(long repositoryId, LocalDate date) {}
