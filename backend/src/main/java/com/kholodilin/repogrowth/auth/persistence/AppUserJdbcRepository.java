package com.kholodilin.repogrowth.auth.persistence;

import com.kholodilin.repogrowth.auth.domain.AppRole;
import com.kholodilin.repogrowth.auth.domain.AppUser;
import com.kholodilin.repogrowth.common.persistence.SqlTime;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class AppUserJdbcRepository {

    private final JdbcClient jdbcClient;

    public AppUserJdbcRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<AppUser> findById(long id) {
        return loadOne("""
                SELECT u.id, u.oidc_issuer, u.oidc_sub, u.display_name, u.enabled,
                       u.last_login_at, u.created_at, u.updated_at, r.role
                FROM app_users u
                LEFT JOIN user_roles r ON r.user_id = u.id
                WHERE u.id = :id
                ORDER BY r.role
                """, Map.of("id", id));
    }

    public Optional<AppUser> findByIssuerAndSub(String issuer, String sub) {
        return loadOne("""
                SELECT u.id, u.oidc_issuer, u.oidc_sub, u.display_name, u.enabled,
                       u.last_login_at, u.created_at, u.updated_at, r.role
                FROM app_users u
                LEFT JOIN user_roles r ON r.user_id = u.id
                WHERE u.oidc_issuer = :issuer AND u.oidc_sub = :sub
                ORDER BY r.role
                """, Map.of("issuer", issuer, "sub", sub));
    }

    public List<AppUser> findAll() {
        List<UserRow> rows = jdbcClient.sql("""
                        SELECT u.id, u.oidc_issuer, u.oidc_sub, u.display_name, u.enabled,
                               u.last_login_at, u.created_at, u.updated_at, r.role
                        FROM app_users u
                        LEFT JOIN user_roles r ON r.user_id = u.id
                        ORDER BY u.id, r.role
                        """)
                .query((rs, rowNum) -> new UserRow(
                        rs.getLong("id"),
                        rs.getString("oidc_issuer"),
                        rs.getString("oidc_sub"),
                        rs.getString("display_name"),
                        rs.getBoolean("enabled"),
                        toInstant(rs.getTimestamp("last_login_at")),
                        toInstant(rs.getTimestamp("created_at")),
                        toInstant(rs.getTimestamp("updated_at")),
                        rs.getString("role")
                ))
                .list();
        return group(rows);
    }

    public AppUser insertUser(String issuer, String sub, String displayName) {
        Long id = jdbcClient.sql("""
                        INSERT INTO app_users (oidc_issuer, oidc_sub, display_name)
                        VALUES (:issuer, :sub, :displayName)
                        RETURNING id
                        """)
                .param("issuer", issuer)
                .param("sub", sub)
                .param("displayName", displayName)
                .query(Long.class)
                .single();
        ensureRole(id, AppRole.USER);
        return findById(id).orElseThrow();
    }

    public void touchLogin(long userId, Instant at) {
        jdbcClient.sql("""
                        UPDATE app_users
                        SET last_login_at = :at, updated_at = NOW()
                        WHERE id = :id
                        """)
                .param("id", userId)
                .param("at", SqlTime.ts(at))
                .update();
    }

    public void ensureRole(long userId, AppRole role) {
        jdbcClient.sql("""
                        INSERT INTO user_roles (user_id, role)
                        VALUES (:userId, :role)
                        ON CONFLICT (user_id, role) DO NOTHING
                        """)
                .param("userId", userId)
                .param("role", role.name())
                .update();
    }

    public void revokeRole(long userId, AppRole role) {
        jdbcClient.sql("""
                        DELETE FROM user_roles
                        WHERE user_id = :userId AND role = :role
                        """)
                .param("userId", userId)
                .param("role", role.name())
                .update();
    }

    private Optional<AppUser> loadOne(String sql, Map<String, Object> params) {
        var query = jdbcClient.sql(sql);
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            query = query.param(entry.getKey(), entry.getValue());
        }
        List<UserRow> rows = query
                .query((rs, rowNum) -> new UserRow(
                        rs.getLong("id"),
                        rs.getString("oidc_issuer"),
                        rs.getString("oidc_sub"),
                        rs.getString("display_name"),
                        rs.getBoolean("enabled"),
                        toInstant(rs.getTimestamp("last_login_at")),
                        toInstant(rs.getTimestamp("created_at")),
                        toInstant(rs.getTimestamp("updated_at")),
                        rs.getString("role")
                ))
                .list();
        List<AppUser> users = group(rows);
        return users.isEmpty() ? Optional.empty() : Optional.of(users.getFirst());
    }

    private static List<AppUser> group(List<UserRow> rows) {
        Map<Long, AppUser> byId = new LinkedHashMap<>();
        for (UserRow row : rows) {
            AppUser existing = byId.get(row.id());
            List<AppRole> roles = existing == null ? new ArrayList<>() : new ArrayList<>(existing.roles());
            if (row.role() != null && !row.role().isBlank()) {
                AppRole parsed = AppRole.valueOf(row.role());
                if (!roles.contains(parsed)) {
                    roles.add(parsed);
                }
            }
            byId.put(row.id(), new AppUser(
                    row.id(),
                    row.oidcIssuer(),
                    row.oidcSub(),
                    row.displayName(),
                    row.enabled(),
                    row.lastLoginAt(),
                    row.createdAt(),
                    row.updatedAt(),
                    List.copyOf(roles)
            ));
        }
        return List.copyOf(byId.values());
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private record UserRow(
            long id,
            String oidcIssuer,
            String oidcSub,
            String displayName,
            boolean enabled,
            Instant lastLoginAt,
            Instant createdAt,
            Instant updatedAt,
            String role
    ) {
    }
}
