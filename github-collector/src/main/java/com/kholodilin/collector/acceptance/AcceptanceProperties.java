package com.kholodilin.collector.acceptance;

import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Trusted result destination refs this node may accept on JobRequest.destinationRef. */
@ConfigurationProperties("collector.acceptance")
public record AcceptanceProperties(List<String> destinations) {
  public AcceptanceProperties {
    destinations =
        destinations == null || destinations.isEmpty()
            ? List.of("rgm-results")
            : List.copyOf(destinations);
  }

  public Set<String> destinationRefs() {
    return Set.copyOf(destinations);
  }
}
