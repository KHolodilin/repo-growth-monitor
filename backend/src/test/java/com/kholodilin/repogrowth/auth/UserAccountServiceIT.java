package com.kholodilin.repogrowth.auth;

import com.kholodilin.repogrowth.auth.application.UserAccountService;
import com.kholodilin.repogrowth.auth.cli.UsersCli;
import com.kholodilin.repogrowth.auth.domain.AppRole;
import com.kholodilin.repogrowth.auth.domain.AppUser;
import com.kholodilin.repogrowth.auth.mock.MockUserCatalog;
import com.kholodilin.repogrowth.support.AbstractPostgresTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class UserAccountServiceIT extends AbstractPostgresTest {

    @Autowired
    UserAccountService users;
    @Autowired
    MockUserCatalog catalog;

    @Test
    void mockSyncCreatesUsersAndAdminBootstrap() {
        catalog.syncMissingUsers();
        AppUser admin = users.findByIssuerAndSub(MockUserCatalog.ISSUER, "admin").orElseThrow();
        AppUser user = users.findByIssuerAndSub(MockUserCatalog.ISSUER, "user").orElseThrow();
        assertThat(admin.isAdmin()).isTrue();
        assertThat(user.isAdmin()).isFalse();
        assertThat(user.hasRole(AppRole.USER)).isTrue();

        users.revokeAdmin(admin.id());
        catalog.syncMissingUsers();
        AppUser after = users.findByIssuerAndSub(MockUserCatalog.ISSUER, "admin").orElseThrow();
        assertThat(after.isAdmin()).isFalse();
        users.grantAdmin(after.id());
        assertThat(users.findById(after.id()).orElseThrow().isAdmin()).isTrue();
    }

    @Test
    void cliListAndGrantRevoke() {
        catalog.syncMissingUsers();
        AppUser user = users.findByIssuerAndSub(MockUserCatalog.ISSUER, "user").orElseThrow();
        UsersCli cli = new UsersCli(users);

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        PrintStream original = System.out;
        System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
        try {
            assertThat(cli.run(new String[]{"users", "list"})).isZero();
        } finally {
            System.setOut(original);
        }
        assertThat(buffer.toString(StandardCharsets.UTF_8)).contains(user.displayName());

        assertThat(cli.run(new String[]{"users", "grant-admin", "--user-id", String.valueOf(user.id())})).isZero();
        assertThat(users.findById(user.id()).orElseThrow().isAdmin()).isTrue();
        assertThat(cli.run(new String[]{"users", "revoke-admin", "--user-id", String.valueOf(user.id())})).isZero();
        assertThat(users.findById(user.id()).orElseThrow().isAdmin()).isFalse();
        assertThat(users.findById(user.id()).orElseThrow().hasRole(AppRole.USER)).isTrue();
    }

    @Test
    void oidcLoginCreatesUserOnly() {
        AppUser created = users.ensureOnLogin("https://idp.example", "sub-1", "Alice");
        assertThat(created.hasRole(AppRole.USER)).isTrue();
        assertThat(created.isAdmin()).isFalse();
        assertThat(created.lastLoginAt()).isNotNull();
        AppUser again = users.ensureOnLogin("https://idp.example", "sub-1", "Alice Updated");
        assertThat(again.id()).isEqualTo(created.id());
    }

    @Test
    void blankDisplayNameFallsBackToSubAndCanMutate() {
        AppUser created = users.ensureOnLogin("https://idp.example", "sub-blank", "  ");
        assertThat(created.displayName()).isEqualTo("sub-blank");
        assertThat(users.canMutate(created)).isFalse();
        users.grantAdmin(created.id());
        AppUser admin = users.findById(created.id()).orElseThrow();
        assertThat(users.canMutate(admin)).isTrue();
        assertThat(users.canMutate(null)).isFalse();
    }
}
