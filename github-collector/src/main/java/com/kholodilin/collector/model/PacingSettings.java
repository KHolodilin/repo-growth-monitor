package com.kholodilin.collector.model;

import java.time.Duration;

/** Local GitHub pacing: concurrency and per-resource minimum interval on this node. */
public record PacingSettings(
    int concurrency, Duration coreInterval, Duration searchInterval, Duration graphqlInterval) {
  public Duration interval(String resource) {
    return switch (resource) {
      case "search" -> searchInterval;
      case "graphql" -> graphqlInterval;
      default -> coreInterval;
    };
  }
}
