package com.kholodilin.repogrowth.auth;

import com.kholodilin.repogrowth.auth.application.UserAccountService;
import com.kholodilin.repogrowth.auth.config.AuthProperties;
import com.kholodilin.repogrowth.auth.config.AuthProvider;
import com.kholodilin.repogrowth.auth.domain.AppRole;
import com.kholodilin.repogrowth.auth.domain.AppUser;
import com.kholodilin.repogrowth.auth.mock.MockUserCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.DefaultResourceLoader;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MockUserCatalogTest {

    @Mock
    UserAccountService users;

    @Test
    void loadsUsersAndSkipsSyncWhenNotMock() {
        AuthProperties properties = new AuthProperties(
                "zitadel", false, Duration.ofHours(1), Duration.ofHours(2), null, null);
        MockUserCatalog catalog = new MockUserCatalog(
                properties, AuthProvider.ZITADEL, users, new DefaultResourceLoader());
        assertThat(catalog.find(null)).isEmpty();
        assertThat(catalog.find("admin")).isPresent();
        assertThat(catalog.all()).hasSize(3);
        catalog.syncMissingUsers();
        verify(users, never()).ensureMockUser(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void requireSyncedCreatesUser() {
        AuthProperties properties = new AuthProperties(
                "mock", false, null, null, null, null);
        MockUserCatalog catalog = new MockUserCatalog(
                properties, AuthProvider.MOCK, users, new DefaultResourceLoader());
        AppUser user = new AppUser(
                1L, "mock", "user", "Mock User", true, null,
                Instant.now(), Instant.now(), List.of(AppRole.USER));
        when(users.ensureMockUser("mock", "user", "Mock User")).thenReturn(user);
        assertThat(catalog.requireSynced("user")).isEqualTo(user);
        assertThatThrownBy(() -> catalog.requireSynced("missing"))
                .isInstanceOf(IllegalArgumentException.class);
        catalog.syncMissingUsers();
        verify(users).ensureMockUser("mock", "admin", "Mock Admin");
    }
}
