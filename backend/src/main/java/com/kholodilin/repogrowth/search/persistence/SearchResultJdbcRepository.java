package com.kholodilin.repogrowth.search.persistence;

import com.kholodilin.repogrowth.common.persistence.SqlTime;
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
            rs.getInt("forks"),
            rs.getString("language"),
            rs.getString("description"),
            toInstant(rs.getTimestamp("repository_created_at")),
            toInstant(rs.getTimestamp("repository_updated_at"))
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
                                stars, forks, language, description, repository_created_at, repository_updated_at
                            ) VALUES (
                                :searchRunId, :position, :githubRepositoryId, :fullName, :owner,
                                :stars, :forks, :language, :description, :repositoryCreatedAt, :repositoryUpdatedAt
                            )
                            """)
                    .param("searchRunId", searchRunId)
                    .param("position", result.position())
                    .param("githubRepositoryId", result.githubRepositoryId())
                    .param("fullName", result.fullName())
                    .param("owner", result.owner())
                    .param("stars", result.stars())
                    .param("forks", result.forks())
                    .param("language", result.language())
                    .param("description", result.description())
                    .param("repositoryCreatedAt", SqlTime.ts(result.repositoryCreatedAt()))
                    .param("repositoryUpdatedAt", SqlTime.ts(result.repositoryUpdatedAt()))
                    .update();
        }
    }

    public List<SearchResult> findByRun(long searchRunId) {
        return jdbcClient.sql("SELECT * FROM search_result WHERE search_run_id = :searchRunId ORDER BY position")
                .param("searchRunId", searchRunId)
                .query(MAPPER)
                .list();
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
