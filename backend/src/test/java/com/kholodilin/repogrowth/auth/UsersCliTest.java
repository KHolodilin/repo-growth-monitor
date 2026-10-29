package com.kholodilin.repogrowth.auth;

import com.kholodilin.repogrowth.auth.application.UserAccountService;
import com.kholodilin.repogrowth.auth.cli.UsersCli;
import com.kholodilin.repogrowth.auth.domain.AppRole;
import com.kholodilin.repogrowth.auth.domain.AppUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UsersCliTest {

    @Mock
    UserAccountService users;

    @Test
    void usageAndUnknownAndBadUserId() {
        UsersCli cli = new UsersCli(users);
        assertThat(cli.run(new String[]{"users"})).isEqualTo(2);
        assertThat(cli.run(new String[]{"users", "explode"})).isEqualTo(2);
        assertThat(cli.run(new String[]{"users", "grant-admin"})).isEqualTo(2);
        assertThat(cli.run(new String[]{"users", "grant-admin", "--user-id", "x"})).isEqualTo(2);
    }

    @Test
    void grantFailureReturnsOne() {
        when(users.grantAdmin(99L)).thenThrow(new IllegalArgumentException("User not found: 99"));
        UsersCli cli = new UsersCli(users);
        assertThat(cli.run(new String[]{"users", "grant-admin", "--user-id", "99"})).isEqualTo(1);
    }

    @Test
    void listPrintsUsers() {
        when(users.list()).thenReturn(List.of(new AppUser(
                1L, "mock", "admin", "Admin", true, Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"),
                List.of(AppRole.USER, AppRole.ADMIN)
        )));
        assertThat(new UsersCli(users).run(new String[]{"users", "list"})).isZero();
    }
}
