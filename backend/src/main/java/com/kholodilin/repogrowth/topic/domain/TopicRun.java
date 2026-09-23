package com.kholodilin.repogrowth.topic.domain;

import com.kholodilin.repogrowth.search.domain.SearchRunStatus;

import java.time.Instant;
import java.time.LocalDate;

public record TopicRun(
        Long id,
        long topicWatchId,
        long repositoryId,
        LocalDate businessDate,
        SearchRunStatus status,
        int attempt,
        Instant nextAttemptAt,
        String lockedBy,
        Instant lockedUntil,
        Instant startedAt,
        Instant completedAt,
        Instant snapshotAt,
        Integer totalCount,
        Integer trackedRepositoryPosition,
        String errorCode,
        String errorMessage
) {
}
