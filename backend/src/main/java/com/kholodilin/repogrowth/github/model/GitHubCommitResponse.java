package com.kholodilin.repogrowth.github.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubCommitResponse(GitHubCommitBody commit) {

    public Instant committedAt() {
        if (commit == null) {
            return null;
        }
        if (commit.committer() != null && commit.committer().date() != null) {
            return commit.committer().date();
        }
        if (commit.author() != null && commit.author().date() != null) {
            return commit.author().date();
        }
        return null;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GitHubCommitBody(GitHubCommitUser author, GitHubCommitUser committer) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GitHubCommitUser(Instant date) {
    }
}
