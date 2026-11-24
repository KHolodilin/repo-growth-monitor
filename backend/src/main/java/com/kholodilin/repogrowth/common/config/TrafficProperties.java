package com.kholodilin.repogrowth.common.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "traffic")
public record TrafficProperties(List<String> servicePaths) {

  private static final List<String> DEFAULT_SERVICE_PATHS =
      List.of(
          "graphs",
          "pulse",
          "actions",
          "settings",
          "network",
          "stargazers",
          "watchers",
          "branches",
          "tags",
          "compare",
          "security",
          "community",
          "activity",
          "forks",
          "deployments",
          "wiki");

  public TrafficProperties {
    if (servicePaths == null || servicePaths.isEmpty()) {
      servicePaths = DEFAULT_SERVICE_PATHS;
    }
  }
}
