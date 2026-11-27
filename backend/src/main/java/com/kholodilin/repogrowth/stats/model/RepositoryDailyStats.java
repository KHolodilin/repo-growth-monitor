package com.kholodilin.repogrowth.stats.model;

import java.time.LocalDate;

/** Tip or historical day of stars/forks/watchers/contributors for one repository. */
public record RepositoryDailyStats(
    LocalDate statDate, int stars, int watchers, int forks, int contributors) {}
