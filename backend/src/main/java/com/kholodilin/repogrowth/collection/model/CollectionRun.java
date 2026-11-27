package com.kholodilin.repogrowth.collection.model;

import java.time.Instant;
import java.time.LocalDate;

public record CollectionRun(
    Long id,
    long repositoryId,
    LocalDate businessDate,
    CollectionRunStatus status,
    int plannedJobs,
    int successfulJobs,
    int failedJobs,
    Instant createdAt,
    Instant completedAt) {}
