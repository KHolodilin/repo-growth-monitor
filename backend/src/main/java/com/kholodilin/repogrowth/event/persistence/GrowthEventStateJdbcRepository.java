package com.kholodilin.repogrowth.event.persistence;

import com.kholodilin.repogrowth.event.domain.GrowthEventState;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;

@Repository
public class GrowthEventStateJdbcRepository {

    private final JdbcClient jdbcClient;
    private final JsonMapper jsonMapper;

    public GrowthEventStateJdbcRepository(JdbcClient jdbcClient, JsonMapper jsonMapper) {
        this.jdbcClient = jdbcClient;
        this.jsonMapper = jsonMapper;
    }

    public GrowthEventState find(long repositoryId) {
        Optional<String> raw = jdbcClient.sql("SELECT state::text FROM growth_event_state WHERE repository_id = :repositoryId")
                .param("repositoryId", repositoryId)
                .query(String.class)
                .optional();
        if (raw.isEmpty() || raw.get().isBlank()) {
            return GrowthEventState.empty();
        }
        return jsonMapper.readValue(raw.get(), GrowthEventState.class);
    }

    public void upsert(long repositoryId, GrowthEventState state) {
        String json = jsonMapper.writeValueAsString(state);
        jdbcClient.sql("""
                        INSERT INTO growth_event_state (repository_id, state, updated_at)
                        VALUES (:repositoryId, CAST(:state AS jsonb), NOW())
                        ON CONFLICT (repository_id) DO UPDATE
                            SET state = EXCLUDED.state,
                                updated_at = NOW()
                        """)
                .param("repositoryId", repositoryId)
                .param("state", json)
                .update();
    }
}
