package com.kholodilin.repogrowth.auth;

import com.kholodilin.repogrowth.auth.config.AuthProvider;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthProviderTest {

    @Test
    void parsesKnownProviders() {
        assertThat(AuthProvider.parse("mock")).isEqualTo(AuthProvider.MOCK);
        assertThat(AuthProvider.parse("ZITADEL")).isEqualTo(AuthProvider.ZITADEL);
        assertThat(AuthProvider.parse("keycloak")).isEqualTo(AuthProvider.KEYCLOAK);
        assertThat(AuthProvider.ZITADEL.isOidc()).isTrue();
        assertThat(AuthProvider.MOCK.isOidc()).isFalse();
    }

    @Test
    void rejectsBlankAndUnknown() {
        assertThatThrownBy(() -> AuthProvider.parse(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUTH_PROVIDER");
        assertThatThrownBy(() -> AuthProvider.parse("ldap"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unknown");
    }
}
