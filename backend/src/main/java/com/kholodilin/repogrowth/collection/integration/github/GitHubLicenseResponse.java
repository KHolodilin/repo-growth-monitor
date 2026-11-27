package com.kholodilin.repogrowth.collection.integration.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubLicenseResponse(
    String key, String name, @JsonProperty("spdx_id") String spdxId) {}
