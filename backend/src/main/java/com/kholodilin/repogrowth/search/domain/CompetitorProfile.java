package com.kholodilin.repogrowth.search.domain;

import java.time.Instant;
import java.util.List;

public record CompetitorProfile(
        long githubRepositoryId,
        String fullName,
        String description,
        String htmlUrl,
        List<String> topics,
        String homepage,
        boolean archived,
        Instant lastCommitAt,
        Instant lastReleaseAt,
        boolean hasReadme,
        boolean readmeHasH1,
        boolean readmeHasName,
        boolean hasLicense,
        boolean hasSocialPreview,
        Instant fetchedAt
) {
}
