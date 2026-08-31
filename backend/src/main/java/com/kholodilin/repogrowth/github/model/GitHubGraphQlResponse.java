package com.kholodilin.repogrowth.github.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubGraphQlResponse(Data data) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Data(Repository repository) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Repository(Connection mentionableUsers) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Connection(int totalCount) {
    }
}
