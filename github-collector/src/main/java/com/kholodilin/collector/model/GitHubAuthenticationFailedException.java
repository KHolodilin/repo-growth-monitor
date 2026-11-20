package com.kholodilin.collector.model;

/** GitHub rejected the credential even after one refresh. Permanent for this assignment. */
public final class GitHubAuthenticationFailedException extends GitHubSourceException {
  public GitHubAuthenticationFailedException() {
    super("GitHub authentication failed");
  }
}
