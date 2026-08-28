package com.kholodilin.repogrowth.repository.persistence;

import com.kholodilin.repogrowth.common.persistence.SqlTime;
import com.kholodilin.repogrowth.repository.domain.Repository;
import com.kholodilin.repogrowth.repository.domain.RepositoryHealthFacts;
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
            rs.getInt("contributors"),
            rs.getBoolean("tracking_enabled"),
            toInstant(rs.getTimestamp("github_created_at")),
            toInstant(rs.getTimestamp("github_updated_at")),
            toInstant(rs.getTimestamp("github_pushed_at")),
            toInstant(rs.getTimestamp("last_commit_at")),
            toInstant(rs.getTimestamp("last_release_at")),
            toInstant(rs.getTimestamp("enriched_at")),
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
                            language, fork, archived, stars, watchers, forks, open_issues, contributors,
                            tracking_enabled, github_created_at, github_updated_at, github_pushed_at,
                            last_commit_at, last_release_at, enriched_at
                        ) VALUES (
                            :githubId, :ownerId, :name, :fullName, :description, :visibility, :defaultBranch,
                            :language, :fork, :archived, :stars, :watchers, :forks, :openIssues, :contributors,
                            FALSE, :githubCreatedAt, :githubUpdatedAt, :githubPushedAt,
                            :lastCommitAt, :lastReleaseAt, :enrichedAt
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
                            contributors = CASE
                                WHEN EXCLUDED.contributors > 0 THEN EXCLUDED.contributors
                                ELSE repository.contributors
                            END,
                            github_created_at = EXCLUDED.github_created_at,
                            github_updated_at = EXCLUDED.github_updated_at,
                            github_pushed_at = COALESCE(EXCLUDED.github_pushed_at, repository.github_pushed_at),
                            last_commit_at = COALESCE(EXCLUDED.last_commit_at, repository.last_commit_at),
                            last_release_at = COALESCE(EXCLUDED.last_release_at, repository.last_release_at),
                            enriched_at = COALESCE(EXCLUDED.enriched_at, repository.enriched_at),
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
                .param("contributors", incoming.contributors())
                .param("githubCreatedAt", SqlTime.ts(incoming.githubCreatedAt()))
                .param("githubUpdatedAt", SqlTime.ts(incoming.githubUpdatedAt()))
                .param("githubPushedAt", SqlTime.ts(incoming.githubPushedAt()))
                .param("lastCommitAt", SqlTime.ts(incoming.lastCommitAt()))
                .param("lastReleaseAt", SqlTime.ts(incoming.lastReleaseAt()))
                .param("enrichedAt", SqlTime.ts(incoming.enrichedAt()))
                .query(MAPPER)
                .single();
    }

    public Optional<Repository> findById(long id) {
        return jdbcClient.sql("SELECT * FROM repository WHERE id = :id")
                .param("id", id)
                .query(MAPPER)
                .optional();
    }

    public Optional<Repository> findByGithubId(long githubId) {
        return jdbcClient.sql("SELECT * FROM repository WHERE github_id = :githubId")
                .param("githubId", githubId)
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

    public void updateStats(
            long id,
            int stars,
            int watchers,
            int forks,
            int openIssues,
            int contributors,
            Instant githubUpdatedAt,
            Instant githubPushedAt,
            Instant lastCommitAt,
            Instant enrichedAt,
            boolean archived
    ) {
        jdbcClient.sql("""
                        UPDATE repository
                        SET stars = :stars,
                            watchers = :watchers,
                            forks = :forks,
                            open_issues = :openIssues,
                            contributors = :contributors,
                            github_updated_at = :githubUpdatedAt,
                            github_pushed_at = :githubPushedAt,
                            last_commit_at = COALESCE(:lastCommitAt, last_commit_at),
                            enriched_at = :enrichedAt,
                            archived = :archived,
                            updated_at = NOW()
                        WHERE id = :id
                        """)
                .param("id", id)
                .param("stars", stars)
                .param("watchers", watchers)
                .param("forks", forks)
                .param("openIssues", openIssues)
                .param("contributors", contributors)
                .param("githubUpdatedAt", SqlTime.ts(githubUpdatedAt))
                .param("githubPushedAt", SqlTime.ts(githubPushedAt))
                .param("lastCommitAt", SqlTime.ts(lastCommitAt))
                .param("enrichedAt", SqlTime.ts(enrichedAt))
                .param("archived", archived)
                .update();
    }

    public void replaceTopics(long repositoryId, List<String> topics) {
        jdbcClient.sql("DELETE FROM repository_topics WHERE repository_id = :id")
                .param("id", repositoryId)
                .update();
        for (String topic : topics) {
            jdbcClient.sql("""
                            INSERT INTO repository_topics (repository_id, topic)
                            VALUES (:id, :topic)
                            """)
                    .param("id", repositoryId)
                    .param("topic", topic)
                    .update();
        }
    }

    public List<String> findTopics(long repositoryId) {
        return jdbcClient.sql("""
                        SELECT topic
                        FROM repository_topics
                        WHERE repository_id = :id
                        ORDER BY topic
                        """)
                .param("id", repositoryId)
                .query(String.class)
                .list();
    }

    public void upsertHealth(long repositoryId, RepositoryHealthFacts facts) {
        jdbcClient.sql("""
                        INSERT INTO repository_health (
                            repository_id, homepage, has_readme, readme_has_h1, readme_has_name,
                            has_license, has_code_of_conduct, has_contributing, has_security_policy,
                            has_issue_template, has_pull_request_template
                        ) VALUES (
                            :id, :homepage, :hasReadme, :readmeHasH1, :readmeHasName,
                            :hasLicense, :hasCodeOfConduct, :hasContributing, :hasSecurityPolicy,
                            :hasIssueTemplate, :hasPullRequestTemplate
                        )
                        ON CONFLICT (repository_id) DO UPDATE SET
                            homepage = EXCLUDED.homepage,
                            has_readme = EXCLUDED.has_readme,
                            readme_has_h1 = EXCLUDED.readme_has_h1,
                            readme_has_name = EXCLUDED.readme_has_name,
                            has_license = EXCLUDED.has_license,
                            has_code_of_conduct = EXCLUDED.has_code_of_conduct,
                            has_contributing = EXCLUDED.has_contributing,
                            has_security_policy = EXCLUDED.has_security_policy,
                            has_issue_template = EXCLUDED.has_issue_template,
                            has_pull_request_template = EXCLUDED.has_pull_request_template,
                            updated_at = NOW()
                        """)
                .param("id", repositoryId)
                .param("homepage", facts.homepage())
                .param("hasReadme", facts.hasReadme())
                .param("readmeHasH1", facts.readmeHasH1())
                .param("readmeHasName", facts.readmeHasName())
                .param("hasLicense", facts.hasLicense())
                .param("hasCodeOfConduct", facts.hasCodeOfConduct())
                .param("hasContributing", facts.hasContributing())
                .param("hasSecurityPolicy", facts.hasSecurityPolicy())
                .param("hasIssueTemplate", facts.hasIssueTemplate())
                .param("hasPullRequestTemplate", facts.hasPullRequestTemplate())
                .update();
    }

    public Optional<RepositoryHealthFacts> findHealth(long repositoryId) {
        return jdbcClient.sql("""
                        SELECT homepage, has_readme, readme_has_h1, readme_has_name, has_license,
                               has_code_of_conduct, has_contributing, has_security_policy,
                               has_issue_template, has_pull_request_template
                        FROM repository_health
                        WHERE repository_id = :id
                        """)
                .param("id", repositoryId)
                .query((rs, rowNum) -> new RepositoryHealthFacts(
                        rs.getString("homepage"),
                        rs.getBoolean("has_readme"),
                        rs.getBoolean("readme_has_h1"),
                        rs.getBoolean("readme_has_name"),
                        rs.getBoolean("has_license"),
                        rs.getBoolean("has_code_of_conduct"),
                        rs.getBoolean("has_contributing"),
                        rs.getBoolean("has_security_policy"),
                        rs.getBoolean("has_issue_template"),
                        rs.getBoolean("has_pull_request_template")
                ))
                .optional();
    }

    public void updateLastReleaseAt(long id, Instant lastReleaseAt) {
        jdbcClient.sql("""
                        UPDATE repository
                        SET last_release_at = :lastReleaseAt, updated_at = NOW()
                        WHERE id = :id
                        """)
                .param("id", id)
                .param("lastReleaseAt", SqlTime.ts(lastReleaseAt))
                .update();
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
