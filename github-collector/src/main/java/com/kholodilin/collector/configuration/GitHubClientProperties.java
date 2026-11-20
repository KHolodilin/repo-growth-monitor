package com.kholodilin.collector.configuration;

import com.kholodilin.collector.model.PacingSettings;
import com.kholodilin.collector.model.RetrySettings;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Local GitHub pacing and technical retry for this collector node. Shared quota remains on RGM
 * ({@code rgm.github-budget.*}).
 */
@ConfigurationProperties("collector.github")
public record GitHubClientProperties(Pacing pacing, Retry retry) {
  public GitHubClientProperties {
    pacing =
        pacing == null
            ? new Pacing(
                1, Duration.ofMillis(1200), Duration.ofMillis(3000), Duration.ofMillis(1200))
            : pacing;
    retry =
        retry == null
            ? new Retry(5, Duration.ofSeconds(5), Duration.ofMinutes(5), 2.0, 0.2)
            : retry;
  }

  public PacingSettings pacingSettings() {
    return new PacingSettings(
        pacing.concurrency(),
        pacing.coreInterval(),
        pacing.searchInterval(),
        pacing.graphqlInterval());
  }

  public RetrySettings retrySettings() {
    return new RetrySettings(
        retry.maxAttempts(),
        retry.initialDelay(),
        retry.maxDelay(),
        retry.multiplier(),
        retry.jitter());
  }

  /** Bucket4j concurrency and per-resource intervals on this VPS. */
  public record Pacing(
      int concurrency, Duration coreInterval, Duration searchInterval, Duration graphqlInterval) {
    public Pacing {
      concurrency = concurrency == 0 ? 1 : concurrency;
      coreInterval = coreInterval == null ? Duration.ofMillis(1200) : coreInterval;
      searchInterval = searchInterval == null ? Duration.ofMillis(3000) : searchInterval;
      graphqlInterval = graphqlInterval == null ? Duration.ofMillis(1200) : graphqlInterval;
      if (concurrency < 1
          || concurrency > 32
          || coreInterval.toMillis() < 1
          || searchInterval.toMillis() < 1
          || graphqlInterval.toMillis() < 1)
        throw new IllegalArgumentException("Invalid GitHub pacing");
    }

    public Duration interval(String resource) {
      return switch (resource) {
        case "search" -> searchInterval;
        case "graphql" -> graphqlInterval;
        default -> coreInterval;
      };
    }
  }

  /** Technical GitHub retries; quota/429 waits do not consume attempts. */
  public record Retry(
      int maxAttempts, Duration initialDelay, Duration maxDelay, double multiplier, double jitter) {
    public Retry {
      maxAttempts = maxAttempts == 0 ? 5 : maxAttempts;
      initialDelay = initialDelay == null ? Duration.ofSeconds(5) : initialDelay;
      maxDelay = maxDelay == null ? Duration.ofMinutes(5) : maxDelay;
      multiplier = multiplier == 0 ? 2.0 : multiplier;
      jitter = jitter == 0 ? 0.2 : jitter;
      if (maxAttempts < 1
          || maxAttempts > 20
          || initialDelay.toMillis() < 1
          || maxDelay.compareTo(initialDelay) < 0
          || multiplier < 1
          || jitter < 0
          || jitter > 1) throw new IllegalArgumentException("Invalid GitHub retry policy");
    }
  }
}
