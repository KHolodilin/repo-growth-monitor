package com.kholodilin.repogrowth.repository.persistence;

import com.kholodilin.repogrowth.common.persistence.SqlTime;
import com.kholodilin.repogrowth.repository.domain.Repository;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
public class RepositoryJdbcRepository {

    static final RowMapper<Repository> MAPPER = (rs, rowNum) -> new Repository(
            rs.getLong("id"),
            rs.getLong("github_id"),
            rs.getLong("owner_id"),
            rs.getString("name"),
            rs.getString("full_name"),
            rs.getString("description"),
            rs.getString("visibility"),
            rs.getString("default_branch"),
            rs.getString("language"),
            rs.getBoolean("fork"),
            rs.getBoolean("archived"),
            rs.getInt("stars"),
            rs.getInt("watchers"),
            rs.getInt("forks"),
            rs.getInt("open_issues"),
            rs.getBoolean("tracking_enabled"),
            toInstant(rs.getTimestamp("github_created_at")),
            toInstant(rs.getTimestamp("github_updated_at")),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at"))
    );

    private final JdbcClient jdbcClient;

    public RepositoryJdbcRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Repository upsertKeepingTracking(Repository incoming) {
        return jdbcClient.sql("""
                        INSERT INTO repository (
                            github_id, owner_id, name, full_name, description, visibility, default_branch,
                            language, fork, archived, stars, watchers, forks, open_issues, tracking_enabled,
                            github_created_at, github_updated_at
                        ) VALUES (
                            :githubId, :ownerId, :name, :fullName, :description, :visibility, :defaultBranch,
                            :language, :fork, :archived, :stars, :watchers, :forks, :openIssues, FALSE,
                            :githubCreatedAt, :githubUpdatedAt
                        )
                        ON CONFLICT (github_id) DO UPDATE SET
                            owner_id = EXCLUDED.owner_id,
                            name = EXCLUDED.name,
                            full_name = EXCLUDED.full_name,
                            description = EXCLUDED.description,
                            visibility = EXCLUDED.visibility,
                            default_branch = EXCLUDED.default_branch,
                            language = EXCLUDED.language,
                            fork = EXCLUDED.fork,
                            archived = EXCLUDED.archived,
                            stars = EXCLUDED.stars,
                            watchers = CASE
                                WHEN EXCLUDED.watchers > 0 THEN EXCLUDED.watchers
                                ELSE repository.watchers
                            END,
                            forks = EXCLUDED.forks,
                            open_issues = EXCLUDED.open_issues,
                            github_created_at = EXCLUDED.github_created_at,
                            github_updated_at = EXCLUDED.github_updated_at,
                            updated_at = NOW()
                        RETURNING *
                        """)
                .param("githubId", incoming.githubId())
                .param("ownerId", incoming.ownerId())
                .param("name", incoming.name())
                .param("fullName", incoming.fullName())
                .param("description", incoming.description())
                .param("visibility", incoming.visibility())
                .param("defaultBranch", incoming.defaultBranch())
                .param("language", incoming.language())
                .param("fork", incoming.fork())
                .param("archived", incoming.archived())
                .param("stars", incoming.stars())
                .param("watchers", incoming.watchers())
                .param("forks", incoming.forks())
                .param("openIssues", incoming.openIssues())
                .param("githubCreatedAt", SqlTime.ts(incoming.githubCreatedAt()))
                .param("githubUpdatedAt", SqlTime.ts(incoming.githubUpdatedAt()))
                .query(MAPPER)
                .single();
    }

    public Optional<Repository> findById(long id) {
        return jdbcClient.sql("SELECT * FROM repository WHERE id = :id")
                .param("id", id)
                .query(MAPPER)
                .optional();
    }

    public List<Repository> findAll() {
        return jdbcClient.sql("SELECT * FROM repository ORDER BY full_name")
                .query(MAPPER)
                .list();
    }

    public List<Repository> findTracked() {
        return jdbcClient.sql("SELECT * FROM repository WHERE tracking_enabled = TRUE ORDER BY full_name")
                .query(MAPPER)
                .list();
    }

    public int countTracked() {
        return jdbcClient.sql("SELECT COUNT(*) FROM repository WHERE tracking_enabled = TRUE")
                .query(Integer.class)
                .single();
    }

    public Repository setTracking(long id, boolean enabled) {
        return jdbcClient.sql("""
                        UPDATE repository
                        SET tracking_enabled = :enabled, updated_at = NOW()
                        WHERE id = :id
                        RETURNING *
                        """)
                .param("enabled", enabled)
                .param("id", id)
                .query(MAPPER)
                .optional()
                .orElse(null);
    }

    public void updateStats(long id, int stars, int watchers, int forks, int openIssues, Instant githubUpdatedAt) {
        jdbcClient.sql("""
                        UPDATE repository
                        SET stars = :stars,
                            watchers = :watchers,
                            forks = :forks,
                            open_issues = :openIssues,
                            github_updated_at = :githubUpdatedAt,
                            updated_at = NOW()
                        WHERE id = :id
                        """)
                .param("stars", stars)
                .param("watchers", watchers)
                .param("forks", forks)
                .param("openIssues", openIssues)
                .param("githubUpdatedAt", SqlTime.ts(githubUpdatedAt))
                .param("id", id)
                .update();
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
