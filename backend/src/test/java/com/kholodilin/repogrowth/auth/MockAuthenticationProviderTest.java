package com.kholodilin.repogrowth.auth;

import com.kholodilin.repogrowth.auth.application.UserAccountService;
import com.kholodilin.repogrowth.auth.config.AuthProvider;
import com.kholodilin.repogrowth.auth.domain.AppRole;
import com.kholodilin.repogrowth.auth.domain.AppUser;
import com.kholodilin.repogrowth.auth.mock.MockAuthenticationProvider;
import com.kholodilin.repogrowth.auth.mock.MockUserCatalog;
import com.kholodilin.repogrowth.auth.mock.MockUserDefinition;
import com.kholodilin.repogrowth.auth.security.AppUserPrincipal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MockAuthenticationProviderTest {

    @Mock
    MockUserCatalog catalog;
    @Mock
    UserAccountService users;

    @Test
    void authenticatesAndRejectsBadCases() {
        MockAuthenticationProvider provider = new MockAuthenticationProvider(AuthProvider.MOCK, catalog, users);
        assertThat(provider.supports(UsernamePasswordAuthenticationToken.class)).isTrue();

        when(catalog.find("missing")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> provider.authenticate(token("missing", "x")))
                .isInstanceOf(BadCredentialsException.class);

        when(catalog.find("user")).thenReturn(Optional.of(new MockUserDefinition("user", "user", "U", true)));
        assertThatThrownBy(() -> provider.authenticate(token("user", "wrong")))
                .isInstanceOf(BadCredentialsException.class);

        when(catalog.find("blocked")).thenReturn(Optional.of(new MockUserDefinition("blocked", "blocked", "B", false)));
        assertThatThrownBy(() -> provider.authenticate(token("blocked", "blocked")))
                .isInstanceOf(DisabledException.class);

        AppUser enabled = new AppUser(1L, "mock", "user", "U", true, null, Instant.now(), Instant.now(), List.of(AppRole.USER));
        when(catalog.find("user")).thenReturn(Optional.of(new MockUserDefinition("user", "user", "U", true)));
        when(catalog.requireSynced("user")).thenReturn(enabled);
        when(users.ensureOnLogin("mock", "user", "U")).thenReturn(enabled);
        when(users.findById(1L)).thenReturn(Optional.of(enabled));
        var auth = provider.authenticate(token("user", "user"));
        assertThat(auth.getPrincipal()).isInstanceOf(AppUserPrincipal.class);

        AppUser disabledDb = new AppUser(2L, "mock", "user", "U", false, null, Instant.now(), Instant.now(), List.of(AppRole.USER));
        when(catalog.requireSynced("user")).thenReturn(disabledDb);
        assertThatThrownBy(() -> provider.authenticate(token("user", "user")))
                .isInstanceOf(DisabledException.class);
    }

    @Test
    void ignoresNonMockProvider() {
        MockAuthenticationProvider provider = new MockAuthenticationProvider(AuthProvider.ZITADEL, catalog, users);
        assertThat(provider.supports(UsernamePasswordAuthenticationToken.class)).isFalse();
        assertThat(provider.authenticate(token("a", "b"))).isNull();
    }

    private static UsernamePasswordAuthenticationToken token(String user, String pass) {
        return UsernamePasswordAuthenticationToken.unauthenticated(user, pass);
    }
}
