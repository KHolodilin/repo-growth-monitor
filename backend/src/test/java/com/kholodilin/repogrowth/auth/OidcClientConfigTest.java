package com.kholodilin.repogrowth.auth;

import com.kholodilin.repogrowth.auth.config.AuthProperties;
import com.kholodilin.repogrowth.auth.config.OidcClientConfig;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class OidcClientConfigTest {

    @Test
    void buildsOidcRegistrationWithDefaults() {
        AuthProperties properties = new AuthProperties(
                "zitadel",
                false,
                Duration.ofHours(8),
                Duration.ofHours(24),
                new AuthProperties.Oidc("https://idp.example", "client", "secret", null, "openid profile email"),
                null
        );
        OidcClientConfig config = new OidcClientConfig();
        ClientRegistrationRepository repo = config.zitadelClientRegistrationRepository(properties);
        ClientRegistration registration = repo.findByRegistrationId("oidc");
        assertThat(registration.getClientId()).isEqualTo("client");
        assertThat(registration.getClientSecret()).isEqualTo("secret");
        assertThat(registration.getScopes()).contains("openid", "profile", "email");
        assertThat(registration.getRedirectUri()).contains("login/oauth2/code");
    }

    @Test
    void keycloakUsesExplicitRedirect() {
        AuthProperties properties = new AuthProperties(
                "keycloak",
                false,
                null,
                null,
                new AuthProperties.Oidc("https://kc.example/realms/x", "cid", "csecret", "https://app/cb", null),
                null
        );
        ClientRegistration registration = new OidcClientConfig()
                .keycloakClientRegistrationRepository(properties)
                .findByRegistrationId("oidc");
        assertThat(registration.getRedirectUri()).isEqualTo("https://app/cb");
        assertThat(registration.getClientName()).isEqualTo("oidc");
    }
}
