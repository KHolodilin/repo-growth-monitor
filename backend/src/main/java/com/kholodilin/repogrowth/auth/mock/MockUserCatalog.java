package com.kholodilin.repogrowth.auth.mock;

import com.kholodilin.repogrowth.auth.application.UserAccountService;
import com.kholodilin.repogrowth.auth.config.AuthProperties;
import com.kholodilin.repogrowth.auth.config.AuthProvider;
import com.kholodilin.repogrowth.auth.domain.AppUser;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class MockUserCatalog {

    public static final String ISSUER = "mock";

    private final Map<String, MockUserDefinition> byUsername;
    private final UserAccountService users;
    private final AuthProvider provider;

    public MockUserCatalog(
            AuthProperties properties,
            AuthProvider provider,
            UserAccountService users,
            ResourceLoader resourceLoader
    ) {
        this.users = users;
        this.provider = provider;
        this.byUsername = load(properties, resourceLoader);
    }

    public Optional<MockUserDefinition> find(String username) {
        if (username == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byUsername.get(username));
    }

    public List<MockUserDefinition> all() {
        return List.copyOf(byUsername.values());
    }

    public void syncMissingUsers() {
        if (provider != AuthProvider.MOCK) {
            return;
        }
        for (MockUserDefinition definition : byUsername.values()) {
            users.ensureMockUser(ISSUER, definition.username(), definition.displayName());
        }
    }

    public AppUser requireSynced(String username) {
        MockUserDefinition definition = find(username)
                .orElseThrow(() -> new IllegalArgumentException("Unknown mock user"));
        return users.ensureMockUser(ISSUER, definition.username(), definition.displayName());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, MockUserDefinition> load(AuthProperties properties, ResourceLoader resourceLoader) {
        Resource resource = resourceLoader.getResource(properties.mock().usersLocation());
        try (InputStream in = resource.getInputStream()) {
            Yaml yaml = new Yaml();
            Map<String, Object> root = yaml.load(in);
            Map<String, MockUserDefinition> result = new LinkedHashMap<>();
            if (root == null) {
                return result;
            }
            Object rawUsers = root.get("users");
            if (!(rawUsers instanceof List<?> list)) {
                return result;
            }
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> map)) {
                    continue;
                }
                String username = stringVal(map.get("username"));
                if (username == null || username.isBlank()) {
                    continue;
                }
                result.put(username, new MockUserDefinition(
                        username,
                        stringVal(map.get("password")),
                        stringVal(map.get("displayName")),
                        booleanVal(map.get("enabled"), true)
                ));
            }
            return result;
        } catch (IOException ex) {
            throw new IllegalStateException("Could not load mock users from " + properties.mock().usersLocation(), ex);
        }
    }

    private static String stringVal(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static boolean booleanVal(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }
}
