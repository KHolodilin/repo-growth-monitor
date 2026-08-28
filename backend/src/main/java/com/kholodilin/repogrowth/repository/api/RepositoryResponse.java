package com.kholodilin.repogrowth.repository.api;

import java.time.Instant;

public record RepositoryResponse(
        long id,
        long githubId,
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
        int contributors,
        int openIssues,
        boolean trackingEnabled,
        Instant githubCreatedAt,
        Instant githubUpdatedAt,
        Instant lastCommitAt,
        String githubUrl,
        String activityStatus,
        Instant lastActivityAt,
        OwnerResponse owner
) {
    public record OwnerResponse(
            long id,
            long githubId,
            String login,
            String ownerType,
            String avatarUrl,
            String htmlUrl
    ) {
    }
}
