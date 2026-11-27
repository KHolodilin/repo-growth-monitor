package com.kholodilin.repogrowth.search.model;

import java.time.Instant;

public record SearchQuery(
    Long id,
    long repositoryId,
    String name,
    String query,
    boolean enabled,
    int resultLimit,
    Instant createdAt,
    Instant updatedAt) {}
