package com.kholodilin.repogrowth.topics.model;

import com.kholodilin.repogrowth.search.model.SearchRunStatus;
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
    String errorMessage) {}
