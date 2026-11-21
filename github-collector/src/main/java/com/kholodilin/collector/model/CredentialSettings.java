package com.kholodilin.collector.model;

import java.net.URI;
import java.time.Duration;
import java.util.List;

/**
 * Plain, already validated credential cache/resolution settings. The configuration layer maps its
 * bound properties to this record; integration code never sees Spring types.
 */
public record CredentialSettings(
    Duration ttl,
    long maximumSize,
    int maximumRejected,
    Duration failureBackoff,
    Duration connectTimeout,
    Duration readTimeout,
    List<Origin> origins) {
  public CredentialSettings {
    origins = origins == null ? List.of() : List.copyOf(origins);
  }

  /** A trusted RGM origin: where to resolve credentials and which service token to present. */
  public record Origin(String origin, URI endpoint, String serviceToken) {
    @Override
    public String toString() {
      return "Origin[origin=" + origin + ", serviceToken=REDACTED]";
    }
  }
}
