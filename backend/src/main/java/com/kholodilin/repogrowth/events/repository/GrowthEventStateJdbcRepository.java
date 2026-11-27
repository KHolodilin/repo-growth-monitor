package com.kholodilin.repogrowth.events.repository;

import com.kholodilin.repogrowth.events.model.GrowthEventState;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class GrowthEventStateJdbcRepository {

  private final JdbcClient jdbcClient;
  private final JsonMapper jsonMapper;

  public GrowthEventStateJdbcRepository(JdbcClient jdbcClient, JsonMapper jsonMapper) {
    this.jdbcClient = jdbcClient;
    // Unknown historical keys stay repository-side — model must stay framework-free.
    this.jsonMapper =
        jsonMapper.rebuild().disable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
  }

  public GrowthEventState find(long repositoryId) {
    Optional<String> raw =
        jdbcClient
            .sql("SELECT state::text FROM growth_event_state WHERE repository_id = :repositoryId")
            .param("repositoryId", repositoryId)
            .query(String.class)
            .optional();
    if (raw.isEmpty() || raw.get().isBlank()) {
      return GrowthEventState.empty();
    }
    return this.jsonMapper.readValue(raw.get(), GrowthEventState.class);
  }

  public void upsert(long repositoryId, GrowthEventState state) {
    String json = jsonMapper.writeValueAsString(state);
    jdbcClient
        .sql(
            """
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
