package com.kholodilin.collector.integration.github;

/** GitHub REST header and Accept values used by the collector integration. */
public final class GitHubHttp {
  public static final String ACCEPT_JSON = "application/vnd.github+json";
  public static final String API_VERSION = "2022-11-28";
  public static final String API_VERSION_HEADER = "X-GitHub-Api-Version";
  public static final String RATE_LIMIT_REMAINING = "X-RateLimit-Remaining";
  public static final String RATE_LIMIT_RESET = "X-RateLimit-Reset";
  public static final String RATE_LIMIT_RESOURCE = "X-RateLimit-Resource";

  private GitHubHttp() {}
}
