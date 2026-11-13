package com.kholodilin.repogrowth.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "github")
public record GitHubProperties(
        String token,
        String apiBaseUrl,
        Duration connectTimeout,
        Duration readTimeout,
        String webBaseUrl
) {
    public GitHubProperties {
        if (webBaseUrl == null || webBaseUrl.isBlank()) {
            webBaseUrl = "https://github.com";
        }
    }

    @Override public String toString() {return "GitHubProperties[token=REDACTED]";}

    public boolean tokenConfigured() {
        return token != null && !token.isBlank();
    }
}
