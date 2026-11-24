package com.kholodilin.collector.model;

/** GitHub denied access and gave no wait hint. Permanent for this assignment. */
public final class GitHubPermissionDeniedException extends GitHubSourceException {
  public GitHubPermissionDeniedException() {
    super("GitHub permission denied");
  }
}
