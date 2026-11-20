package com.kholodilin.collector.delivery;

import java.net.URI;
import java.time.Duration;
import java.util.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

@ConfigurationProperties("collector.delivery")
public record DeliveryProperties(
    boolean enabled,
    Duration lease,
    Duration retryDelay,
    Duration pollInterval,
    int maximumAttempts,
    boolean allowHttp,
    List<Destination> destinations,
    @NestedConfigurationProperty Retry retry) {
  public DeliveryProperties {
    lease = lease == null ? Duration.ofSeconds(60) : lease;
    retryDelay = retryDelay == null ? Duration.ofSeconds(5) : retryDelay;
    pollInterval = pollInterval == null ? Duration.ofMillis(500) : pollInterval;
    maximumAttempts = maximumAttempts == 0 ? 12 : maximumAttempts;
    destinations = destinations == null ? List.of() : List.copyOf(destinations);
    retry =
        retry == null
            ? new Retry(maximumAttempts, retryDelay, Duration.ofMinutes(5), 2.0, 0.2)
            : retry;
    if (lease.compareTo(Duration.ofSeconds(30)) < 0
        || lease.compareTo(Duration.ofMinutes(10)) > 0
        || retryDelay.toMillis() < 1
        || pollInterval.toMillis() < 1
        || maximumAttempts < 1
        || maximumAttempts > 100)
      throw new IllegalArgumentException("Invalid result delivery policy");
    Set<String> names = new HashSet<>();
    for (var destination : destinations) {
      var uri = destination.endpoint();
      if (destination.ref() == null
          || destination.origin() == null
          || !names.add(destination.origin() + ":" + destination.ref())
          || uri == null
          || uri.getHost() == null
          || uri.getUserInfo() != null
          || uri.getQuery() != null
          || uri.getFragment() != null
          || !("https".equals(uri.getScheme()) || (allowHttp && "http".equals(uri.getScheme()))))
        throw new IllegalArgumentException("Trusted unique result destinations are required");
    }
    if (enabled && destinations.isEmpty())
      throw new IllegalArgumentException("Result delivery destination is required");
  }

  public record Retry(
      int maxAttempts, Duration initialDelay, Duration maxDelay, double multiplier, double jitter) {
    public Retry {
      maxAttempts = maxAttempts == 0 ? 12 : maxAttempts;
      initialDelay = initialDelay == null ? Duration.ofSeconds(5) : initialDelay;
      maxDelay = maxDelay == null ? Duration.ofMinutes(5) : maxDelay;
      multiplier = multiplier == 0 ? 2.0 : multiplier;
      jitter = jitter == 0 ? 0.2 : jitter;
      if (maxAttempts < 1
          || maxAttempts > 100
          || initialDelay.toMillis() < 1
          || maxDelay.compareTo(initialDelay) < 0
          || multiplier < 1
          || jitter < 0
          || jitter > 1) throw new IllegalArgumentException("Invalid delivery retry policy");
    }
  }

  public record Destination(String ref, String origin, URI endpoint) {}
}
