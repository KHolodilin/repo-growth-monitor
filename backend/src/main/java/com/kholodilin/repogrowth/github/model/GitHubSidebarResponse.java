package com.kholodilin.repogrowth.github.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubSidebarResponse(Contributors contributors) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Contributors(Integer contributorCount) {
    }
}
