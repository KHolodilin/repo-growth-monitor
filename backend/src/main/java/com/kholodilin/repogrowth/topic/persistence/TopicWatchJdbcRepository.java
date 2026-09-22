package com.kholodilin.repogrowth.topic.persistence;

import com.kholodilin.repogrowth.topic.domain.TopicWatch;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class TopicWatchJdbcRepository {

    static final RowMapper<TopicWatch> MAPPER = (rs, rowNum) -> new TopicWatch(
            rs.getLong("id"),
            rs.getLong("repository_id"),
            rs.getString("topic"),
            rs.getString("language"),
            rs.getString("sort"),
            rs.getString("sort_order"),
            rs.getBoolean("enabled"),
            rs.getInt("result_limit"),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at"))
    );

    private final JdbcClient jdbcClient;

    public TopicWatchJdbcRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public TopicWatch insert(long repositoryId, String topic, String language, int resultLimit) {
        return jdbcClient.sql("""
                        INSERT INTO topic_watch (repository_id, topic, language, result_limit)
                        VALUES (:repositoryId, :topic, :language, :resultLimit)
                        RETURNING *
                        """)
                .param("repositoryId", repositoryId)
                .param("topic", topic)
                .param("language", language)
                .param("resultLimit", resultLimit)
                .query(MAPPER)
                .single();
    }

    public void setEnabled(long id, boolean enabled) {
        jdbcClient.sql("""
                        UPDATE topic_watch
                        SET enabled = :enabled, updated_at = NOW()
                        WHERE id = :id
                        """)
                .param("id", id)
                .param("enabled", enabled)
                .update();
    }

    public Optional<TopicWatch> findById(long id) {
        return jdbcClient.sql("SELECT * FROM topic_watch WHERE id = :id")
                .param("id", id)
                .query(MAPPER)
                .optional();
    }

    public List<TopicWatch> findByRepository(long repositoryId) {
        return jdbcClient.sql("""
                        SELECT * FROM topic_watch
                        WHERE repository_id = :repositoryId
                        ORDER BY topic, language NULLS FIRST
                        """)
                .param("repositoryId", repositoryId)
                .query(MAPPER)
                .list();
    }

    public List<TopicWatch> findEnabledByRepository(long repositoryId, boolean allLanguages) {
        String languageClause = allLanguages ? "language IS NULL" : "language IS NOT NULL";
        return jdbcClient.sql("""
                        SELECT * FROM topic_watch
                        WHERE repository_id = :repositoryId
                          AND enabled = TRUE
                          AND %s
                        ORDER BY topic
                        """.formatted(languageClause))
                .param("repositoryId", repositoryId)
                .query(MAPPER)
                .list();
    }

    public List<TopicWatch> findEnabled() {
        return jdbcClient.sql("""
                        SELECT w.*
                        FROM topic_watch w
                        JOIN repository r ON r.id = w.repository_id
                        WHERE w.enabled = TRUE
                          AND r.account_accessible = TRUE
                          AND r.tracking_enabled = TRUE
                        """)
                .query(MAPPER)
                .list();
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
