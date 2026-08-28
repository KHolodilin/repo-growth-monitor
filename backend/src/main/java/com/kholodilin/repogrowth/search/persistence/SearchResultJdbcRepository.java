package com.kholodilin.repogrowth.search.persistence;

import com.kholodilin.repogrowth.common.persistence.SqlTime;
import com.kholodilin.repogrowth.search.domain.ActivityStatus;
import com.kholodilin.repogrowth.search.domain.SearchResult;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Repository
public class SearchResultJdbcRepository {

    static final RowMapper<SearchResult> MAPPER = (rs, rowNum) -> new SearchResult(
            rs.getLong("id"),
            rs.getLong("search_run_id"),
            rs.getInt("position"),
            rs.getLong("github_repository_id"),
            rs.getString("full_name"),
            rs.getString("owner"),
            rs.getInt("stars"),
            rs.getInt("watchers"),
            rs.getInt("forks"),
            rs.getInt("contributors"),
            rs.getString("language"),
            rs.getString("description"),
            rs.getString("html_url"),
            toInstant(rs.getTimestamp("repository_created_at")),
            toInstant(rs.getTimestamp("repository_updated_at")),
            toInstant(rs.getTimestamp("activity_at")),
            activityStatus(rs.getString("activity_status")),
            toInstant(rs.getTimestamp("metadata_updated_at"))
    );

    private final JdbcClient jdbcClient;

    public SearchResultJdbcRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void replaceAll(long searchRunId, List<SearchResult> results) {
        jdbcClient.sql("DELETE FROM search_result WHERE search_run_id = :searchRunId")
                .param("searchRunId", searchRunId)
                .update();
        for (SearchResult result : results) {
            jdbcClient.sql("""
                            INSERT INTO search_result (
                                search_run_id, position, github_repository_id, full_name, owner,
                                stars, watchers, forks, contributors, language, description, html_url,
                                repository_created_at, repository_updated_at, activity_at, activity_status,
                                metadata_updated_at
                            ) VALUES (
                                :searchRunId, :position, :githubRepositoryId, :fullName, :owner,
                                :stars, :watchers, :forks, :contributors, :language, :description, :htmlUrl,
                                :repositoryCreatedAt, :repositoryUpdatedAt, :activityAt, :activityStatus,
                                :metadataUpdatedAt
                            )
                            """)
                    .param("searchRunId", searchRunId)
                    .param("position", result.position())
                    .param("githubRepositoryId", result.githubRepositoryId())
                    .param("fullName", result.fullName())
                    .param("owner", result.owner())
                    .param("stars", result.stars())
                    .param("watchers", result.watchers())
                    .param("forks", result.forks())
                    .param("contributors", result.contributors())
                    .param("language", result.language())
                    .param("description", result.description())
                    .param("htmlUrl", result.htmlUrl())
                    .param("repositoryCreatedAt", SqlTime.ts(result.repositoryCreatedAt()))
                    .param("repositoryUpdatedAt", SqlTime.ts(result.repositoryUpdatedAt()))
                    .param("activityAt", SqlTime.ts(result.activityAt()))
                    .param("activityStatus", result.activityStatus() == null ? null : result.activityStatus().name())
                    .param("metadataUpdatedAt", SqlTime.ts(result.metadataUpdatedAt()))
                    .update();
        }
    }

    public void updateSnapshot(SearchResult result) {
        jdbcClient.sql("""
                        UPDATE search_result
                        SET stars = :stars,
                            watchers = :watchers,
                            forks = :forks,
                            contributors = :contributors,
                            language = :language,
                            description = :description,
                            html_url = :htmlUrl,
                            repository_created_at = :repositoryCreatedAt,
                            repository_updated_at = :repositoryUpdatedAt,
                            activity_at = :activityAt,
                            activity_status = :activityStatus,
                            metadata_updated_at = :metadataUpdatedAt
                        WHERE id = :id
                        """)
                .param("id", result.id())
                .param("stars", result.stars())
                .param("watchers", result.watchers())
                .param("forks", result.forks())
                .param("contributors", result.contributors())
                .param("language", result.language())
                .param("description", result.description())
                .param("htmlUrl", result.htmlUrl())
                .param("repositoryCreatedAt", SqlTime.ts(result.repositoryCreatedAt()))
                .param("repositoryUpdatedAt", SqlTime.ts(result.repositoryUpdatedAt()))
                .param("activityAt", SqlTime.ts(result.activityAt()))
                .param("activityStatus", result.activityStatus() == null ? null : result.activityStatus().name())
                .param("metadataUpdatedAt", SqlTime.ts(result.metadataUpdatedAt()))
                .update();
    }

    public List<SearchResult> findByRun(long searchRunId) {
        return jdbcClient.sql("SELECT * FROM search_result WHERE search_run_id = :searchRunId ORDER BY position")
                .param("searchRunId", searchRunId)
                .query(MAPPER)
                .list();
    }

    private static ActivityStatus activityStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return ActivityStatus.valueOf(value);
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
