package com.kholodilin.repogrowth.repository.persistence;

import com.kholodilin.repogrowth.repository.domain.GitHubOwner;
import com.kholodilin.repogrowth.repository.domain.OwnerType;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;

@Repository
public class GitHubOwnerJdbcRepository {

    private static final RowMapper<GitHubOwner> MAPPER = (rs, rowNum) -> new GitHubOwner(
            rs.getLong("id"),
            rs.getLong("github_id"),
            rs.getString("login"),
            OwnerType.valueOf(rs.getString("owner_type")),
            rs.getString("avatar_url"),
            rs.getString("html_url"),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at"))
    );

    private final JdbcClient jdbcClient;

    public GitHubOwnerJdbcRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public GitHubOwner upsert(long githubId, String login, OwnerType type, String avatarUrl, String htmlUrl) {
        return jdbcClient.sql("""
                        INSERT INTO github_owner (github_id, login, owner_type, avatar_url, html_url)
                        VALUES (:githubId, :login, :ownerType, :avatarUrl, :htmlUrl)
                        ON CONFLICT (github_id) DO UPDATE SET
                            login = EXCLUDED.login,
                            owner_type = EXCLUDED.owner_type,
                            avatar_url = EXCLUDED.avatar_url,
                            html_url = EXCLUDED.html_url,
                            updated_at = NOW()
                        RETURNING *
                        """)
                .param("githubId", githubId)
                .param("login", login)
                .param("ownerType", type.name())
                .param("avatarUrl", avatarUrl)
                .param("htmlUrl", htmlUrl)
                .query(MAPPER)
                .single();
    }

    public GitHubOwner getById(long id) {
        return jdbcClient.sql("SELECT * FROM github_owner WHERE id = :id")
                .param("id", id)
                .query(MAPPER)
                .optional()
                .orElse(null);
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
