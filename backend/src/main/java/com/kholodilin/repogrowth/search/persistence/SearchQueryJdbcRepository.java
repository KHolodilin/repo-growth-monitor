package com.kholodilin.repogrowth.search.persistence;

import com.kholodilin.repogrowth.search.domain.SearchQuery;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class SearchQueryJdbcRepository {

    static final RowMapper<SearchQuery> MAPPER = (rs, rowNum) -> new SearchQuery(
            rs.getLong("id"),
            rs.getLong("repository_id"),
            rs.getString("name"),
            rs.getString("query"),
            rs.getBoolean("enabled"),
            rs.getInt("result_limit"),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at"))
    );

    private final JdbcClient jdbcClient;

    public SearchQueryJdbcRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public SearchQuery insert(long repositoryId, String name, String query, boolean enabled, int resultLimit) {
        return jdbcClient.sql("""
                        INSERT INTO search_query (repository_id, name, query, enabled, result_limit)
                        VALUES (:repositoryId, :name, :query, :enabled, :resultLimit)
                        RETURNING *
                        """)
                .param("repositoryId", repositoryId)
                .param("name", name)
                .param("query", query)
                .param("enabled", enabled)
                .param("resultLimit", resultLimit)
                .query(MAPPER)
                .single();
    }

    public SearchQuery update(long id, String name, String query, boolean enabled, int resultLimit) {
        return jdbcClient.sql("""
                        UPDATE search_query
                        SET name = :name,
                            query = :query,
                            enabled = :enabled,
                            result_limit = :resultLimit,
                            updated_at = NOW()
                        WHERE id = :id
                        RETURNING *
                        """)
                .param("id", id)
                .param("name", name)
                .param("query", query)
                .param("enabled", enabled)
                .param("resultLimit", resultLimit)
                .query(MAPPER)
                .optional()
                .orElse(null);
    }

    public Optional<SearchQuery> findById(long id) {
        return jdbcClient.sql("SELECT * FROM search_query WHERE id = :id")
                .param("id", id)
                .query(MAPPER)
                .optional();
    }

    public List<SearchQuery> findByRepository(long repositoryId) {
        return jdbcClient.sql("SELECT * FROM search_query WHERE repository_id = :repositoryId ORDER BY created_at")
                .param("repositoryId", repositoryId)
                .query(MAPPER)
                .list();
    }

    public List<SearchQuery> findEnabled() {
        return jdbcClient.sql("SELECT * FROM search_query WHERE enabled = TRUE")
                .query(MAPPER)
                .list();
    }

    public void delete(long id) {
        jdbcClient.sql("DELETE FROM search_query WHERE id = :id")
                .param("id", id)
                .update();
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
