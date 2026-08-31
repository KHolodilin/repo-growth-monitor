package com.kholodilin.repogrowth.event.domain;

import java.time.Instant;

public record GrowthEventSetting(
        long repositoryId,
        String eventType,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt
) {
}
