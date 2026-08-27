package com.kholodilin.repogrowth.github.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubRepositoryResponse(
        long id,
        String name,
        @JsonProperty("full_name") String fullName,
        String description,
        @JsonProperty("private") boolean privateRepository,
        String visibility,
        @JsonProperty("default_branch") String defaultBranch,
        String language,
        boolean fork,
        boolean archived,
        @JsonProperty("stargazers_count") int stargazersCount,
        @JsonProperty("subscribers_count") Integer subscribersCount,
        @JsonProperty("forks_count") int forksCount,
        @JsonProperty("open_issues_count") int openIssuesCount,
        @JsonProperty("html_url") String htmlUrl,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt,
        GitHubOwnerResponse owner
) {
    public String resolvedVisibility() {
        if (visibility != null && !visibility.isBlank()) {
            return visibility.toUpperCase();
        }
        return privateRepository ? "PRIVATE" : "PUBLIC";
    }

    public int watchers() {
        return subscribersCount == null ? 0 : subscribersCount;
    }
}
