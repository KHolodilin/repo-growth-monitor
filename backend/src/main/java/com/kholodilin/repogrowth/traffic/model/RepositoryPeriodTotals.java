package com.kholodilin.repogrowth.traffic.model;

import java.time.Instant;

/** Per-repository traffic totals for a period, with tip catalog fields for portfolio rows. */
public record RepositoryPeriodTotals(
    long repositoryId,
    String fullName,
    long uniqueVisitors,
    long views,
    long clones,
    int stars,
    int forks,
    boolean archived,
    Instant lastCommitAt,
    Instant githubPushedAt) {}
