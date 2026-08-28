package com.kholodilin.repogrowth.search.domain;

import java.time.Instant;

public record SearchResult(
        Long id,
        long searchRunId,
        int position,
        long githubRepositoryId,
        String fullName,
        String owner,
        int stars,
        int watchers,
        int forks,
        int contributors,
        String language,
        String description,
        String htmlUrl,
        Instant repositoryCreatedAt,
        Instant repositoryUpdatedAt,
        Instant activityAt,
        ActivityStatus activityStatus,
        Instant metadataUpdatedAt
) {
}
