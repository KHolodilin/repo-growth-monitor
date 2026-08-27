package com.kholodilin.repogrowth.github.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubTrafficViewsResponse(
        int count,
        int uniques,
        List<GitHubTrafficDay> views
) {
    public List<GitHubTrafficDay> days() {
        return views == null ? List.of() : views;
    }
}
