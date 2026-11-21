package com.kholodilin.collector.model;

/**
 * GitHub (or the path to it) failed temporarily: transport error, 5xx, redirect, timeout. The
 * service retries these under the bounded technical retry policy.
 */
public final class GitHubSourceUnavailableException extends GitHubSourceException {
  public GitHubSourceUnavailableException() {
    super("GitHub source temporarily unavailable");
  }
}
