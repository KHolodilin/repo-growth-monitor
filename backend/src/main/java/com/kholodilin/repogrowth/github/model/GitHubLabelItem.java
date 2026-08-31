package com.kholodilin.repogrowth.github.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubLabelItem(String name) {
    public boolean goodFirstIssue() {
        return name != null && "good first issue".equalsIgnoreCase(name.trim());
    }
}
