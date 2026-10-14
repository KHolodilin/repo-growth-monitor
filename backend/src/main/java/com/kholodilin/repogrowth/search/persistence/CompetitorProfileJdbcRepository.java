package com.kholodilin.repogrowth.search.persistence;

import com.kholodilin.repogrowth.common.persistence.SqlTime;
import com.kholodilin.repogrowth.search.domain.CompetitorProfile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Repository
public class CompetitorProfileJdbcRepository {

    private final JdbcClient jdbcClient;

    public CompetitorProfileJdbcRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<CompetitorProfile> find(long githubRepositoryId) {
        return jdbcClient.sql("""
                        SELECT github_repository_id, full_name, description, html_url, topics, homepage, archived,
                               last_commit_at, last_release_at, has_readme, readme_has_h1, readme_has_name,
                               has_license, has_social_preview, fetched_at
                        FROM competitor_profile
                        WHERE github_repository_id = :githubRepositoryId
                        """)
                .param("githubRepositoryId", githubRepositoryId)
                .query((rs, rowNum) -> new CompetitorProfile(
                        rs.getLong("github_repository_id"),
                        rs.getString("full_name"),
                        rs.getString("description"),
                        rs.getString("html_url"),
                        topics(rs.getString("topics")),
                        rs.getString("homepage"),
                        rs.getBoolean("archived"),
                        toInstant(rs.getTimestamp("last_commit_at")),
                        toInstant(rs.getTimestamp("last_release_at")),
                        rs.getBoolean("has_readme"),
                        rs.getBoolean("readme_has_h1"),
                        rs.getBoolean("readme_has_name"),
                        rs.getBoolean("has_license"),
                        rs.getBoolean("has_social_preview"),
                        rs.getTimestamp("fetched_at").toInstant()
                ))
                .optional();
    }

    public void save(CompetitorProfile profile) {
        jdbcClient.sql("""
                        INSERT INTO competitor_profile (
                            github_repository_id, full_name, description, html_url, topics, homepage, archived,
                            last_commit_at, last_release_at, has_readme, readme_has_h1, readme_has_name,
                            has_license, has_social_preview, fetched_at
                        ) VALUES (
                            :githubRepositoryId, :fullName, :description, :htmlUrl, :topics, :homepage, :archived,
                            :lastCommitAt, :lastReleaseAt, :hasReadme, :readmeHasH1, :readmeHasName,
                            :hasLicense, :hasSocialPreview, :fetchedAt
                        )
                        ON CONFLICT (github_repository_id) DO UPDATE SET
                            full_name = EXCLUDED.full_name,
                            description = EXCLUDED.description,
                            html_url = EXCLUDED.html_url,
                            topics = EXCLUDED.topics,
                            homepage = EXCLUDED.homepage,
                            archived = EXCLUDED.archived,
                            last_commit_at = EXCLUDED.last_commit_at,
                            last_release_at = EXCLUDED.last_release_at,
                            has_readme = EXCLUDED.has_readme,
                            readme_has_h1 = EXCLUDED.readme_has_h1,
                            readme_has_name = EXCLUDED.readme_has_name,
                            has_license = EXCLUDED.has_license,
                            has_social_preview = EXCLUDED.has_social_preview,
                            fetched_at = EXCLUDED.fetched_at
                        """)
                .param("githubRepositoryId", profile.githubRepositoryId())
                .param("fullName", profile.fullName())
                .param("description", profile.description())
                .param("htmlUrl", profile.htmlUrl())
                .param("topics", String.join("\n", profile.topics()))
                .param("homepage", profile.homepage())
                .param("archived", profile.archived())
                .param("lastCommitAt", SqlTime.ts(profile.lastCommitAt()))
                .param("lastReleaseAt", SqlTime.ts(profile.lastReleaseAt()))
                .param("hasReadme", profile.hasReadme())
                .param("readmeHasH1", profile.readmeHasH1())
                .param("readmeHasName", profile.readmeHasName())
                .param("hasLicense", profile.hasLicense())
                .param("hasSocialPreview", profile.hasSocialPreview())
                .param("fetchedAt", SqlTime.ts(profile.fetchedAt()))
                .update();
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static List<String> topics(String stored) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        return Arrays.stream(stored.split("\n")).filter(topic -> !topic.isBlank()).toList();
    }
}
