package com.kholodilin.collector.model;

import java.net.URI;
import java.time.Duration;
import java.util.List;

/** Plain, already validated result delivery settings. */
public record DeliverySettings(
    Duration lease, Duration pollInterval, RetrySettings retry, List<Destination> destinations) {
  public DeliverySettings {
    destinations = destinations == null ? List.of() : List.copyOf(destinations);
  }

  /** A trusted result destination: the endpoint RGM exposes for one origin/destination pair. */
  public record Destination(String ref, String origin, URI endpoint) {}
}
