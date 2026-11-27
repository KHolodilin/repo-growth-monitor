package com.kholodilin.repogrowth.stats.model;

import java.time.LocalDate;
import java.util.List;

/**
 * Tracked-repository stats day used by portfolio aggregates. {@code contributorLogins} is null when
 * logins were not collected for that tip.
 */
public record RepositoryStatDay(
    long repositoryId,
    String fullName,
    LocalDate date,
    int stars,
    int forks,
    int watchers,
    int contributors,
    List<String> contributorLogins) {

  public RepositoryStatDay(
      long repositoryId,
      String fullName,
      LocalDate date,
      int stars,
      int forks,
      int watchers,
      int contributors) {
    this(repositoryId, fullName, date, stars, forks, watchers, contributors, null);
  }
}
