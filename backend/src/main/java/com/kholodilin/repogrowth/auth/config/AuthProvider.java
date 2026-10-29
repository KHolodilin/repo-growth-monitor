package com.kholodilin.repogrowth.auth.config;

import java.util.Locale;

public enum AuthProvider {
    MOCK,
    ZITADEL,
    KEYCLOAK;

    public static AuthProvider parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("AUTH_PROVIDER is required (mock|zitadel|keycloak)");
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "mock" -> MOCK;
            case "zitadel" -> ZITADEL;
            case "keycloak" -> KEYCLOAK;
            default -> throw new IllegalStateException(
                    "Unknown AUTH_PROVIDER '" + raw + "'. Expected mock|zitadel|keycloak"
            );
        };
    }

    public boolean isOidc() {
        return this == ZITADEL || this == KEYCLOAK;
    }

    public String issuerLabel() {
        return name().toLowerCase(Locale.ROOT);
    }
}
