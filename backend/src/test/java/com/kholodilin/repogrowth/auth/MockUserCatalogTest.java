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
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

    @Test
    void loadHandlesEmptyMalformedAndBooleanStrings() {
        String yaml = """
                users:
                  - not-a-map
                  - username: ""
                  - username: "ok"
                    password: "p"
                    displayName: "Ok"
                    enabled: "false"
                  - username: "on"
                    enabled: true
                """;
        AuthProperties properties = new AuthProperties(
                "mock", false, null, null, null, new AuthProperties.Mock("mem:users"));
        MockUserCatalog catalog = new MockUserCatalog(
                properties, AuthProvider.MOCK, users, resourceLoader(yaml));
        assertThat(catalog.find("ok")).isPresent();
        assertThat(catalog.find("ok").orElseThrow().enabled()).isFalse();
        assertThat(catalog.find("on")).isPresent();
        assertThat(catalog.find("")).isEmpty();
    }

    @Test
    void loadReturnsEmptyWhenRootOrUsersMissing() {
        AuthProperties properties = new AuthProperties(
                "mock", false, null, null, null, new AuthProperties.Mock("mem:empty"));
        assertThat(new MockUserCatalog(properties, AuthProvider.MOCK, users, resourceLoader("")).all()).isEmpty();
        assertThat(new MockUserCatalog(properties, AuthProvider.MOCK, users, resourceLoader("users: {}")).all()).isEmpty();
        assertThat(new MockUserCatalog(properties, AuthProvider.MOCK, users, resourceLoader("other: 1")).all()).isEmpty();
    }

    @Test
    void loadWrapsIoException() {
        AuthProperties properties = new AuthProperties(
                "mock", false, null, null, null, new AuthProperties.Mock("broken"));
        ResourceLoader loader = new ResourceLoader() {
            @Override
            public Resource getResource(String location) {
                return new Resource() {
                    @Override
                    public java.io.InputStream getInputStream() throws IOException {
                        throw new IOException("boom");
                    }

                    @Override
                    public boolean exists() {
                        return true;
                    }

                    @Override
                    public java.net.URL getURL() {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public java.net.URI getURI() {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public java.io.File getFile() {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public long contentLength() {
                        return 0;
                    }

                    @Override
                    public long lastModified() {
                        return 0;
                    }

                    @Override
                    public Resource createRelative(String relativePath) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public String getFilename() {
                        return "broken";
                    }

                    @Override
                    public String getDescription() {
                        return "broken";
                    }
                };
            }

            @Override
            public ClassLoader getClassLoader() {
                return getClass().getClassLoader();
            }
        };
        assertThatThrownBy(() -> new MockUserCatalog(properties, AuthProvider.MOCK, users, loader))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Could not load mock users");
    }

    private static ResourceLoader resourceLoader(String yaml) {
        byte[] bytes = yaml.getBytes(StandardCharsets.UTF_8);
        return new ResourceLoader() {
            @Override
            public Resource getResource(String location) {
                return new ByteArrayResource(bytes) {
                    @Override
                    public String getDescription() {
                        return location;
                    }
                };
            }

            @Override
            public ClassLoader getClassLoader() {
                return getClass().getClassLoader();
            }
        };
    }
}
