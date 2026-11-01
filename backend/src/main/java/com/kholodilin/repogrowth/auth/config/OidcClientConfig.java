package com.kholodilin.repogrowth.auth.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;

import java.util.Locale;

@Configuration
public class OidcClientConfig {

    @Bean
    @ConditionalOnProperty(name = "auth.provider", havingValue = "zitadel")
    public ClientRegistrationRepository zitadelClientRegistrationRepository(AuthProperties properties) {
        return new InMemoryClientRegistrationRepository(oidcRegistration(properties));
    }

    @Bean
    @ConditionalOnProperty(name = "auth.provider", havingValue = "keycloak")
    public ClientRegistrationRepository keycloakClientRegistrationRepository(AuthProperties properties) {
        return new InMemoryClientRegistrationRepository(oidcRegistration(properties));
    }

    static ClientRegistration oidcRegistration(AuthProperties properties) {
        AuthProperties.Oidc oidc = properties.oidc();
        String issuer = oidc.issuerUri().replaceAll("/$", "");
        String redirect = oidc.redirectUri() == null || oidc.redirectUri().isBlank()
                ? "{baseUrl}/login/oauth2/code/{registrationId}"
                : oidc.redirectUri();
        boolean keycloak = properties.provider() != null
                && "keycloak".equals(properties.provider().trim().toLowerCase(Locale.ROOT));
        String authorizationUri = keycloak
                ? issuer + "/protocol/openid-connect/auth"
                : issuer + "/oauth/v2/authorize";
        String tokenUri = keycloak
                ? issuer + "/protocol/openid-connect/token"
                : issuer + "/oauth/v2/token";
        String jwkSetUri = keycloak
                ? issuer + "/protocol/openid-connect/certs"
                : issuer + "/oauth/v2/keys";
        String userInfoUri = keycloak
                ? issuer + "/protocol/openid-connect/userinfo"
                : issuer + "/oidc/v1/userinfo";
        return ClientRegistration.withRegistrationId("oidc")
                .clientId(oidc.clientId())
                .clientSecret(oidc.clientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(redirect)
                .scope(properties.scopes().toArray(String[]::new))
                .authorizationUri(authorizationUri)
                .tokenUri(tokenUri)
                .jwkSetUri(jwkSetUri)
                .userInfoUri(userInfoUri)
                .userNameAttributeName(IdTokenClaimNames.SUB)
                .issuerUri(issuer)
                .clientName("oidc")
                .build();
    }
}
