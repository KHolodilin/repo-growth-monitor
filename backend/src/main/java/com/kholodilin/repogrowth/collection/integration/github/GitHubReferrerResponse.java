package com.kholodilin.repogrowth.collection.integration.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubReferrerResponse(String referrer, int count, int uniques) {}
