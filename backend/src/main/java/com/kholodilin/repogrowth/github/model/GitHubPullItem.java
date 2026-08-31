package com.kholodilin.repogrowth.github.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubPullItem(
        long id,
        int number,
        String title,
        String state,
        Boolean merged,
        @JsonProperty("html_url") String htmlUrl,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("merged_at") Instant mergedAt,
        GitHubOwnerResponse user
) {
    public boolean mergedPull() {
        return Boolean.TRUE.equals(merged) || mergedAt != null;
    }
}
