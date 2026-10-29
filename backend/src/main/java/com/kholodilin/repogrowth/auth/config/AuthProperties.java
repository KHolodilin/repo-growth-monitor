package com.kholodilin.repogrowth.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "auth")
public record AuthProperties(
        String provider,
        boolean production,
        Duration sessionIdle,
        Duration sessionAbsolute,
        Oidc oidc,
        Mock mock
) {
    public AuthProperties {
        if (sessionIdle == null) {
            sessionIdle = Duration.ofHours(8);
        }
        if (sessionAbsolute == null) {
            sessionAbsolute = Duration.ofHours(24);
        }
        if (oidc == null) {
            oidc = new Oidc(null, null, null, null, "openid profile email");
        }
        if (mock == null) {
            mock = new Mock("classpath:auth/mock-users.yml");
        }
    }

    public record Oidc(
            String issuerUri,
            String clientId,
            String clientSecret,
            String redirectUri,
            String scope
    ) {
        public Oidc {
            if (scope == null || scope.isBlank()) {
                scope = "openid profile email";
            }
        }
    }

    public record Mock(String usersLocation) {
        public Mock {
            if (usersLocation == null || usersLocation.isBlank()) {
                usersLocation = "classpath:auth/mock-users.yml";
            }
        }
    }

    public List<String> scopes() {
        return List.of(oidc.scope().trim().split("\\s+"));
    }
}
