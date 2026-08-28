package com.kholodilin.repogrowth.github.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubReleaseResponse(
        boolean draft,
        @JsonProperty("published_at") Instant publishedAt,
        @JsonProperty("created_at") Instant createdAt
) {
    public Instant timestamp() {
        return publishedAt != null ? publishedAt : createdAt;
    }
}
