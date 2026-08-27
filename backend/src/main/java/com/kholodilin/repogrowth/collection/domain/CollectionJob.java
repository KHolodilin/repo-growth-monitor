package com.kholodilin.repogrowth.collection.domain;

import java.time.Instant;
import java.time.LocalDate;

public record CollectionJob(
        Long id,
        long collectionRunId,
        long repositoryId,
        CollectionJobType jobType,
        LocalDate businessDate,
        CollectionJobStatus status,
        int attempt,
        Instant nextAttemptAt,
        String lockedBy,
        Instant lockedUntil,
        Instant startedAt,
        Instant completedAt,
        String errorCode,
        String errorMessage
) {
}
