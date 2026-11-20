package com.kholodilin.collector.model;

/** The assigned GitHub source does not exist (or is not visible). Permanent for this assignment. */
public final class GitHubNotFoundException extends GitHubSourceException {
  public GitHubNotFoundException() {
    super("GitHub source not found");
  }
}
