package com.kholodilin.repogrowth.search.domain;

import java.time.Instant;
import java.time.LocalDate;

public record SearchRun(
        Long id,
        long searchQueryId,
        long repositoryId,
        LocalDate businessDate,
        SearchRunStatus status,
        int attempt,
        Instant nextAttemptAt,
        String lockedBy,
        Instant lockedUntil,
        Instant startedAt,
        Instant completedAt,
        Integer totalCount,
        Integer trackedRepositoryPosition,
        String errorCode,
        String errorMessage
) {
}
