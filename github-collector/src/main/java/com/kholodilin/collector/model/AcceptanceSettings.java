package com.kholodilin.collector.model;

import java.util.Set;

/** Result destination refs this node accepts on {@code JobRequest.destinationRef}. */
public record AcceptanceSettings(Set<String> destinationRefs) {
  public AcceptanceSettings {
    destinationRefs = Set.copyOf(destinationRefs);
  }
}
