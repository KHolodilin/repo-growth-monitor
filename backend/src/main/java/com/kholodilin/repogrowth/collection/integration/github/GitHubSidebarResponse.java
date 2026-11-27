package com.kholodilin.repogrowth.collection.integration.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubSidebarResponse(Contributors contributors) {

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Contributors(Integer contributorCount) {}
}
