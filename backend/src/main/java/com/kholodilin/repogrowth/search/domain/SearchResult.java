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
        int forks,
        String language,
        String description,
        Instant repositoryCreatedAt,
        Instant repositoryUpdatedAt
) {
}
