package com.kholodilin.repogrowth.event.domain;

import java.time.Instant;

public record GrowthEvent(
        Long id,
        long repositoryId,
        Instant eventAt,
        String category,
        String type,
        String title,
        String description,
        String url,
        String source,
        String externalId,
        Instant createdAt,
        Instant updatedAt
) {
    public boolean manual() {
        return GrowthEventCatalog.SOURCE_MANUAL.equals(source);
    }
}
