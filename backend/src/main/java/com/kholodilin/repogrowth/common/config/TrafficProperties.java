package com.kholodilin.repogrowth.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "traffic")
public record TrafficProperties(List<String> servicePaths) {

    private static final List<String> DEFAULT_SERVICE_PATHS = List.of(
            "graphs",
            "pulse",
            "pulls",
            "pull",
            "issues",
            "actions",
            "commits",
            "commit",
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
            "wiki"
    );

    public TrafficProperties {
        if (servicePaths == null || servicePaths.isEmpty()) {
            servicePaths = DEFAULT_SERVICE_PATHS;
        }
    }
}
