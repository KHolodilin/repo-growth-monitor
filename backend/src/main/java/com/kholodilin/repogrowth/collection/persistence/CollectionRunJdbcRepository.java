package com.kholodilin.repogrowth.collection.persistence;

import com.kholodilin.repogrowth.collection.domain.CollectionRun;
import com.kholodilin.repogrowth.collection.domain.CollectionRunStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

@Repository
public class CollectionRunJdbcRepository {

    private static final RowMapper<CollectionRun> MAPPER = (rs, rowNum) -> new CollectionRun(
            rs.getLong("id"),
            rs.getLong("repository_id"),
            rs.getObject("business_date", LocalDate.class),
            CollectionRunStatus.valueOf(rs.getString("status")),
            rs.getInt("planned_jobs"),
            rs.getInt("successful_jobs"),
            rs.getInt("failed_jobs"),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("completed_at"))
    );

    private final JdbcClient jdbcClient;

    public CollectionRunJdbcRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public CollectionRun insertIgnore(long repositoryId, LocalDate businessDate, int plannedJobs) {
        jdbcClient.sql("""
                        INSERT INTO collection_run (repository_id, business_date, status, planned_jobs)
                        VALUES (:repositoryId, :businessDate, 'PLANNED', :plannedJobs)
                        ON CONFLICT (repository_id, business_date) DO NOTHING
                        """)
                .param("repositoryId", repositoryId)
                .param("businessDate", businessDate)
                .param("plannedJobs", plannedJobs)
                .update();
        return find(repositoryId, businessDate).orElseThrow();
    }

    public Optional<CollectionRun> find(long repositoryId, LocalDate businessDate) {
        return jdbcClient.sql("""
                        SELECT * FROM collection_run
                        WHERE repository_id = :repositoryId AND business_date = :businessDate
                        """)
                .param("repositoryId", repositoryId)
                .param("businessDate", businessDate)
                .query(MAPPER)
                .optional();
    }

    public Optional<CollectionRun> findById(long id) {
        return jdbcClient.sql("SELECT * FROM collection_run WHERE id = :id")
                .param("id", id)
                .query(MAPPER)
                .optional();
    }

    public Optional<CollectionRun> latestForRepository(long repositoryId) {
        return jdbcClient.sql("""
                        SELECT * FROM collection_run
                        WHERE repository_id = :repositoryId
                        ORDER BY business_date DESC, created_at DESC
                        LIMIT 1
                        """)
                .param("repositoryId", repositoryId)
                .query(MAPPER)
                .optional();
    }

    public void refreshAggregates(long runId) {
        jdbcClient.sql("""
                        UPDATE collection_run r
                        SET planned_jobs = stats.planned,
                            successful_jobs = stats.successful,
                            failed_jobs = stats.failed,
                            status = stats.status,
                            completed_at = CASE
                                WHEN stats.status IN ('SUCCESS', 'PARTIAL', 'FAILED') THEN NOW()
                                ELSE r.completed_at
                            END
                        FROM (
                            SELECT
                                collection_run_id,
                                COUNT(*) AS planned,
                                COUNT(*) FILTER (WHERE status = 'SUCCESS') AS successful,
                                COUNT(*) FILTER (WHERE status = 'FAILED') AS failed,
                                COUNT(*) FILTER (WHERE status IN ('READY', 'RUNNING', 'RETRY')) AS pending,
                                CASE
                                    WHEN COUNT(*) FILTER (WHERE status IN ('READY', 'RUNNING', 'RETRY')) > 0
                                         AND COUNT(*) FILTER (WHERE status IN ('RUNNING', 'SUCCESS', 'FAILED', 'RETRY')) = 0
                                        THEN 'PLANNED'
                                    WHEN COUNT(*) FILTER (WHERE status IN ('READY', 'RUNNING', 'RETRY')) > 0
                                        THEN 'RUNNING'
                                    WHEN COUNT(*) FILTER (WHERE status = 'FAILED') = COUNT(*)
                                        THEN 'FAILED'
                                    WHEN COUNT(*) FILTER (WHERE status = 'SUCCESS') = COUNT(*)
                                        THEN 'SUCCESS'
                                    ELSE 'PARTIAL'
                                END AS status
                            FROM collection_job
                            WHERE collection_run_id = :runId
                            GROUP BY collection_run_id
                        ) stats
                        WHERE r.id = stats.collection_run_id
                        """)
                .param("runId", runId)
                .update();
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
