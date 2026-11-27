package com.kholodilin.repogrowth.collection.integration.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubTrafficClonesResponse(int count, int uniques, List<GitHubTrafficDay> clones) {
  public List<GitHubTrafficDay> days() {
    return clones == null ? List.of() : clones;
  }
}
