package com.kholodilin.repogrowth.auth;

import com.kholodilin.repogrowth.auth.config.AuthConfig;
import com.kholodilin.repogrowth.auth.config.AuthProperties;
import com.kholodilin.repogrowth.auth.config.AuthProvider;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthConfigTest {

    private final AuthConfig config = new AuthConfig();

    @Test
    void acceptsMockOutsideProduction() {
        AuthProperties properties = new AuthProperties(
                "mock",
                false,
                Duration.ofHours(1),
                Duration.ofHours(2),
                null,
                null
        );
        assertThat(config.authProvider(properties)).isEqualTo(AuthProvider.MOCK);
        assertThat(properties.sessionIdle()).isEqualTo(Duration.ofHours(1));
        assertThat(properties.mock().usersLocation()).contains("mock-users");
    }

    @Test
    void rejectsMockInProduction() {
        AuthProperties properties = new AuthProperties("mock", true, null, null, null, null);
        assertThatThrownBy(() -> config.authProvider(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("forbidden");
    }

    @Test
    void requiresOidcFields() {
        AuthProperties missingIssuer = new AuthProperties(
                "zitadel",
                true,
                null,
                null,
                new AuthProperties.Oidc(null, "id", "secret", null, null),
                null
        );
        assertThatThrownBy(() -> config.authProvider(missingIssuer))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("issuer-uri");

        AuthProperties missingClient = new AuthProperties(
                "keycloak",
                false,
                null,
                null,
                new AuthProperties.Oidc("https://idp.example", " ", "secret", null, "openid"),
                null
        );
        assertThatThrownBy(() -> config.authProvider(missingClient))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("client-id");

        AuthProperties missingSecret = new AuthProperties(
                "zitadel",
                false,
                null,
                null,
                new AuthProperties.Oidc("https://idp.example", "client", "", null, null),
                null
        );
        assertThatThrownBy(() -> config.authProvider(missingSecret))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("client-secret");
    }

    @Test
    void acceptsCompleteOidc() {
        AuthProperties properties = new AuthProperties(
                "zitadel",
                true,
                null,
                null,
                new AuthProperties.Oidc("https://idp.example", "client", "secret", "https://app/callback", "openid profile"),
                null
        );
        assertThat(config.authProvider(properties)).isEqualTo(AuthProvider.ZITADEL);
        assertThat(properties.scopes()).containsExactly("openid", "profile");
    }
}
