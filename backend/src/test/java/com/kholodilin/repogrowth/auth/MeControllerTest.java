package com.kholodilin.repogrowth.auth;

import com.kholodilin.repogrowth.auth.api.MeController;
import com.kholodilin.repogrowth.auth.application.UserAccountService;
import com.kholodilin.repogrowth.auth.config.AuthProvider;
import com.kholodilin.repogrowth.auth.domain.AppRole;
import com.kholodilin.repogrowth.auth.domain.AppUser;
import com.kholodilin.repogrowth.auth.security.AppUserPrincipal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MeControllerTest {

    @Mock
    UserAccountService users;

    @Test
    void anonymousForMissingDisabledOrWrongPrincipal() {
        MeController controller = new MeController(users, AuthProvider.ZITADEL);
        assertThat(controller.me(null).loginUrl()).isEqualTo("/oauth2/authorization/oidc");
        assertThat(controller.me(null).authenticated()).isFalse();

        Authentication wrong = UsernamePasswordAuthenticationToken.authenticated("x", null, List.of());
        assertThat(controller.me(wrong).authenticated()).isFalse();

        AppUserPrincipal principal = new AppUserPrincipal(9L, "Gone");
        Authentication auth = UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of());
        when(users.findById(9L)).thenReturn(Optional.empty());
        assertThat(controller.me(auth).authenticated()).isFalse();

        when(users.findById(9L)).thenReturn(Optional.of(new AppUser(
                9L, "mock", "gone", "Gone", false,
                null, Instant.now(), Instant.now(), List.of(AppRole.USER))));
        assertThat(controller.me(auth).authenticated()).isFalse();
    }

    @Test
    void authenticatedAdminIncludesMutate() {
        MeController controller = new MeController(users, AuthProvider.MOCK);
        AppUser admin = new AppUser(
                1L, "mock", "admin", "Admin", true,
                Instant.now(), Instant.now(), Instant.now(),
                List.of(AppRole.USER, AppRole.ADMIN));
        when(users.findById(1L)).thenReturn(Optional.of(admin));
        AppUserPrincipal principal = new AppUserPrincipal(1L, "Admin");
        Authentication auth = UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of());
        MeController.MeResponse me = controller.me(auth);
        assertThat(me.authenticated()).isTrue();
        assertThat(me.actions()).containsExactly("MUTATE");
        assertThat(me.loginUrl()).isEqualTo("/login");
    }
}
