package com.kholodilin.repogrowth.github.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubSearchResponse(
        @JsonProperty("total_count") int totalCount,
        List<GitHubSearchItem> items
) {
    public List<GitHubSearchItem> itemsOrEmpty() {
        return items == null ? List.of() : items;
    }
}
