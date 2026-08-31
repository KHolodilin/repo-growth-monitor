package com.kholodilin.repogrowth.event.persistence;

import com.kholodilin.repogrowth.event.domain.GrowthEventCatalog;
import com.kholodilin.repogrowth.event.domain.GrowthEventSetting;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Repository
public class GrowthEventSettingJdbcRepository {

    static final RowMapper<GrowthEventSetting> MAPPER = (rs, rowNum) -> new GrowthEventSetting(
            rs.getLong("repository_id"),
            rs.getString("event_type"),
            rs.getBoolean("enabled"),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at"))
    );

    private final JdbcClient jdbcClient;

    public GrowthEventSettingJdbcRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public List<GrowthEventSetting> ensureDefaults(long repositoryId) {
        for (Map.Entry<String, Boolean> entry : GrowthEventCatalog.automaticDefaults().entrySet()) {
            jdbcClient.sql("""
                            INSERT INTO growth_event_setting (repository_id, event_type, enabled)
                            VALUES (:repositoryId, :eventType, :enabled)
                            ON CONFLICT (repository_id, event_type) DO NOTHING
                            """)
                    .param("repositoryId", repositoryId)
                    .param("eventType", entry.getKey())
                    .param("enabled", entry.getValue())
                    .update();
        }
        return findByRepository(repositoryId);
    }

    public List<GrowthEventSetting> findByRepository(long repositoryId) {
        return jdbcClient.sql("""
                        SELECT * FROM growth_event_setting
                        WHERE repository_id = :repositoryId
                        ORDER BY event_type
                        """)
                .param("repositoryId", repositoryId)
                .query(MAPPER)
                .list();
    }

    public List<GrowthEventSetting> replace(long repositoryId, Map<String, Boolean> enabledByType) {
        ensureDefaults(repositoryId);
        List<GrowthEventSetting> updated = new ArrayList<>();
        for (GrowthEventSetting setting : findByRepository(repositoryId)) {
            if (!enabledByType.containsKey(setting.eventType())) {
                updated.add(setting);
                continue;
            }
            boolean enabled = enabledByType.get(setting.eventType());
            updated.add(jdbcClient.sql("""
                            UPDATE growth_event_setting
                            SET enabled = :enabled, updated_at = NOW()
                            WHERE repository_id = :repositoryId AND event_type = :eventType
                            RETURNING *
                            """)
                    .param("repositoryId", repositoryId)
                    .param("eventType", setting.eventType())
                    .param("enabled", enabled)
                    .query(MAPPER)
                    .single());
        }
        return updated;
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
