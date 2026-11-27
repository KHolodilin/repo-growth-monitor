package com.kholodilin.repogrowth.catalog.model;

import java.time.Instant;

public record GitHubOwner(
    Long id,
    long githubId,
    String login,
    OwnerType ownerType,
    String avatarUrl,
    String htmlUrl,
    Instant createdAt,
    Instant updatedAt) {}
