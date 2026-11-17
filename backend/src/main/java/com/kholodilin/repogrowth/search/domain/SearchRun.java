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
        // Set when the run stored its results and kept when the row is queued for another attempt.
        Instant snapshotAt,
        Integer totalCount,
        Integer trackedRepositoryPosition,
        String enrichmentStatus,
        /** GitHub search sort for this snapshot: best_match or stars/forks/… */
        String sortMode,
        String errorCode,
        String errorMessage
) {
}
