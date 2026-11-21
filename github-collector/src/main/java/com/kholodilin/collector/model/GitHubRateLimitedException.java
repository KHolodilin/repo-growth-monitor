package com.kholodilin.collector.model;

import java.time.Instant;
import java.util.Objects;

/** GitHub asked the collector to wait; the wait never spends the technical retry budget. */
public final class GitHubRateLimitedException extends GitHubSourceException {
  private final Instant retryAt;

  public GitHubRateLimitedException(Instant retryAt) {
    super("GitHub rate limit or quota deferral");
    this.retryAt = Objects.requireNonNull(retryAt);
  }

  public Instant retryAt() {
    return retryAt;
  }
}
