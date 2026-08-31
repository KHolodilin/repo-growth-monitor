package com.kholodilin.repogrowth.event.persistence;

import com.kholodilin.repogrowth.common.persistence.SqlTime;
import com.kholodilin.repogrowth.event.detect.CandidateEvent;
import com.kholodilin.repogrowth.event.domain.GrowthEvent;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public class GrowthEventJdbcRepository {

    static final RowMapper<GrowthEvent> MAPPER = (rs, rowNum) -> new GrowthEvent(
            rs.getLong("id"),
            rs.getLong("repository_id"),
            toInstant(rs.getTimestamp("event_at")),
            rs.getString("category"),
            rs.getString("type"),
            rs.getString("title"),
            rs.getString("description"),
            rs.getString("url"),
            rs.getString("source"),
            rs.getString("external_id"),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at"))
    );

    private final JdbcClient jdbcClient;

    public GrowthEventJdbcRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public Optional<GrowthEvent> insertIgnore(long repositoryId, CandidateEvent candidate) {
        return jdbcClient.sql("""
                        INSERT INTO growth_event (
                            repository_id, event_at, category, type, title, description, url, source, external_id
                        ) VALUES (
                            :repositoryId, :eventAt, :category, :type, :title, :description, :url, :source, :externalId
                        )
                        ON CONFLICT DO NOTHING
                        RETURNING *
                        """)
                .param("repositoryId", repositoryId)
                .param("eventAt", SqlTime.ts(candidate.eventAt()))
                .param("category", candidate.category())
                .param("type", candidate.type())
                .param("title", candidate.title())
                .param("description", candidate.description())
                .param("url", candidate.url())
                .param("source", candidate.source())
                .param("externalId", candidate.externalId())
                .query(MAPPER)
                .optional();
    }

    public GrowthEvent insertManual(
            long repositoryId,
            Instant eventAt,
            String category,
            String type,
            String title,
            String description,
            String url
    ) {
        return jdbcClient.sql("""
                        INSERT INTO growth_event (
                            repository_id, event_at, category, type, title, description, url, source
                        ) VALUES (
                            :repositoryId, :eventAt, :category, :type, :title, :description, :url, 'MANUAL'
                        )
                        RETURNING *
                        """)
                .param("repositoryId", repositoryId)
                .param("eventAt", SqlTime.ts(eventAt))
                .param("category", category)
                .param("type", type)
                .param("title", title)
                .param("description", description)
                .param("url", url)
                .query(MAPPER)
                .single();
    }

    public Optional<GrowthEvent> updateManual(long id, Instant eventAt, String type, String category, String title, String description, String url) {
        return jdbcClient.sql("""
                        UPDATE growth_event
                        SET event_at = :eventAt,
                            type = :type,
                            category = :category,
                            title = :title,
                            description = :description,
                            url = :url,
                            updated_at = NOW()
                        WHERE id = :id AND source = 'MANUAL'
                        RETURNING *
                        """)
                .param("id", id)
                .param("eventAt", SqlTime.ts(eventAt))
                .param("type", type)
                .param("category", category)
                .param("title", title)
                .param("description", description)
                .param("url", url)
                .query(MAPPER)
                .optional();
    }

    public boolean deleteManual(long id) {
        return jdbcClient.sql("DELETE FROM growth_event WHERE id = :id AND source = 'MANUAL'")
                .param("id", id)
                .update() > 0;
    }

    public Optional<GrowthEvent> findById(long id) {
        return jdbcClient.sql("SELECT * FROM growth_event WHERE id = :id")
                .param("id", id)
                .query(MAPPER)
                .optional();
    }

    public List<GrowthEvent> findInPeriod(long repositoryId, LocalDate from, LocalDate to) {
        return jdbcClient.sql("""
                        SELECT *
                        FROM growth_event
                        WHERE repository_id = :repositoryId
                          AND (event_at AT TIME ZONE 'UTC')::date BETWEEN :from AND :to
                        ORDER BY event_at DESC, id DESC
                        """)
                .param("repositoryId", repositoryId)
                .param("from", from)
                .param("to", to)
                .query(MAPPER)
                .list();
    }

    public List<GrowthEvent> findRecent(long repositoryId, int limit) {
        return jdbcClient.sql("""
                        SELECT *
                        FROM growth_event
                        WHERE repository_id = :repositoryId
                        ORDER BY event_at DESC, id DESC
                        LIMIT :limit
                        """)
                .param("repositoryId", repositoryId)
                .param("limit", limit)
                .query(MAPPER)
                .list();
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
