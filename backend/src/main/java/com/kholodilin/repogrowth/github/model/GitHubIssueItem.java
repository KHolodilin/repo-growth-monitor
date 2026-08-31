package com.kholodilin.repogrowth.github.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubIssueItem(
        long id,
        int number,
        String title,
        String state,
        @JsonProperty("html_url") String htmlUrl,
        @JsonProperty("created_at") Instant createdAt,
        GitHubOwnerResponse user,
        List<GitHubLabelItem> labels,
        @JsonProperty("pull_request") Object pullRequest
) {
    public boolean pullRequestIssue() {
        return pullRequest != null;
    }

    public boolean hasGoodFirstIssueLabel() {
        if (labels == null) {
            return false;
        }
        return labels.stream().anyMatch(GitHubLabelItem::goodFirstIssue);
    }
}
