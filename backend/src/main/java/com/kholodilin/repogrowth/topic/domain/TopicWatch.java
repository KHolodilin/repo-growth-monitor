package com.kholodilin.repogrowth.topic.domain;

import java.time.Instant;

public record TopicWatch(
        Long id,
        long repositoryId,
        String topic,
        String language,
        String sort,
        String sortOrder,
        boolean enabled,
        int resultLimit,
        Instant createdAt,
        Instant updatedAt
) {
    public String searchQuery() {
        if (language == null || language.isBlank()) {
            return "topic:" + topic;
        }
        return "topic:" + topic + " language:" + language;
    }

    public boolean allLanguages() {
        return language == null || language.isBlank();
    }
}
