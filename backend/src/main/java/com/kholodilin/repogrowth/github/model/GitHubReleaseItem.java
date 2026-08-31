package com.kholodilin.repogrowth.github.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubReleaseItem(
        long id,
        Boolean draft,
        @JsonProperty("tag_name") String tagName,
        String name,
        @JsonProperty("html_url") String htmlUrl,
        @JsonProperty("published_at") Instant publishedAt,
        @JsonProperty("created_at") Instant createdAt
) {
    public Instant timestamp() {
        return publishedAt != null ? publishedAt : createdAt;
    }

    public String displayName() {
        if (name != null && !name.isBlank()) {
            return name;
        }
        return tagName == null || tagName.isBlank() ? "Release" : tagName;
    }
}
