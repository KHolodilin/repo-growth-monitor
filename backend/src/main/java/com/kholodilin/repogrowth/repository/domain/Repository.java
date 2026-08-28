package com.kholodilin.repogrowth.repository.domain;

import java.time.Instant;

public record Repository(
        Long id,
        long githubId,
        long ownerId,
        String name,
        String fullName,
        String description,
        String visibility,
        String defaultBranch,
        String language,
        boolean fork,
        boolean archived,
        int stars,
        int watchers,
        int forks,
        int openIssues,
        int contributors,
        boolean trackingEnabled,
        Instant githubCreatedAt,
        Instant githubUpdatedAt,
        Instant githubPushedAt,
        Instant lastCommitAt,
        Instant lastReleaseAt,
        Instant enrichedAt,
        Instant createdAt,
        Instant updatedAt
) {
    public Instant activityAt() {
        return lastCommitAt != null ? lastCommitAt : githubPushedAt;
    }
}
