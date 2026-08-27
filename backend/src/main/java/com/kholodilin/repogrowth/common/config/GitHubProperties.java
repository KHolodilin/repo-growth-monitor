package com.kholodilin.repogrowth.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "github")
public record GitHubProperties(
        String token,
        String apiBaseUrl,
        Duration connectTimeout,
        Duration readTimeout
) {
    public boolean tokenConfigured() {
        return token != null && !token.isBlank();
    }
}
