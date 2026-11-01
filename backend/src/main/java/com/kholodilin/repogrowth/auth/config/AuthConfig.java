package com.kholodilin.repogrowth.auth.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class AuthConfig {

    @Bean
    public AuthProvider authProvider(AuthProperties properties) {
        AuthProvider provider = AuthProvider.parse(properties.provider());
        if (properties.production() && provider == AuthProvider.MOCK) {
            throw new IllegalStateException("AUTH_PROVIDER=mock is forbidden when auth.production=true");
        }
        if (provider.isOidc()) {
            AuthProperties.Oidc oidc = properties.oidc();
            if (oidc.issuerUri() == null || oidc.issuerUri().isBlank()) {
                throw new IllegalStateException("auth.oidc.issuer-uri is required for AUTH_PROVIDER=" + provider.issuerLabel());
            }
            if (oidc.clientId() == null || oidc.clientId().isBlank()) {
                throw new IllegalStateException("auth.oidc.client-id is required for AUTH_PROVIDER=" + provider.issuerLabel());
            }
            if (oidc.clientSecret() == null || oidc.clientSecret().isBlank()) {
                throw new IllegalStateException("auth.oidc.client-secret is required for AUTH_PROVIDER=" + provider.issuerLabel());
            }
        }
        return provider;
    }
}
