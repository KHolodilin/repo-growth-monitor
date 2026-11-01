package com.kholodilin.repogrowth.auth;

import com.kholodilin.repogrowth.auth.application.UserAccountService;
import com.kholodilin.repogrowth.auth.domain.AppRole;
import com.kholodilin.repogrowth.auth.domain.AppUser;
import com.kholodilin.repogrowth.auth.security.AppUserPrincipal;
import com.kholodilin.repogrowth.auth.security.CurrentUserAccess;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CurrentUserAccessTest {

    @Mock
    UserAccountService users;

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void guestsAndUsersCannotSeePrivate() {
        CurrentUserAccess access = new CurrentUserAccess(users);
        assertThat(access.canSeePrivateRepositories()).isFalse();

        AppUser user = new AppUser(
                2L, "mock", "user", "User", true,
                Instant.now(), Instant.now(), Instant.now(), List.of(AppRole.USER));
        when(users.findById(2L)).thenReturn(Optional.of(user));
        when(users.canMutate(user)).thenReturn(false);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        new AppUserPrincipal(2L, "User"), null, List.of()));
        assertThat(access.canSeePrivateRepositories()).isFalse();
    }

    @Test
    void adminsCanSeePrivate() {
        CurrentUserAccess access = new CurrentUserAccess(users);
        AppUser admin = new AppUser(
                1L, "mock", "admin", "Admin", true,
                Instant.now(), Instant.now(), Instant.now(), List.of(AppRole.USER, AppRole.ADMIN));
        when(users.findById(1L)).thenReturn(Optional.of(admin));
        when(users.canMutate(admin)).thenReturn(true);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        new AppUserPrincipal(1L, "Admin"), null, List.of()));
        assertThat(access.canSeePrivateRepositories()).isTrue();
    }
}
