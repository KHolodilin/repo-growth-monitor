package com.kholodilin.repogrowth.collection.integration.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubTrafficViewsResponse(int count, int uniques, List<GitHubTrafficDay> views) {
  public List<GitHubTrafficDay> days() {
    return views == null ? List.of() : views;
  }
}
