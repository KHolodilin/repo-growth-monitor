package com.kholodilin.repogrowth.collection.persistence;

import com.kholodilin.repogrowth.collection.domain.CollectionJob;
import com.kholodilin.repogrowth.common.persistence.SqlTime;
import com.kholodilin.repogrowth.collection.domain.CollectionJobStatus;
import com.kholodilin.repogrowth.collection.domain.CollectionJobType;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public class CollectionJobJdbcRepository {

    static final RowMapper<CollectionJob> MAPPER = (rs, rowNum) -> new CollectionJob(
            rs.getLong("id"),
            rs.getLong("collection_run_id"),
            rs.getLong("repository_id"),
            CollectionJobType.valueOf(rs.getString("job_type")),
            rs.getObject("business_date", LocalDate.class),
            CollectionJobStatus.valueOf(rs.getString("status")),
            rs.getInt("attempt"),
            toInstant(rs.getTimestamp("next_attempt_at")),
            rs.getString("locked_by"),
            toInstant(rs.getTimestamp("locked_until")),
            toInstant(rs.getTimestamp("started_at")),
            toInstant(rs.getTimestamp("completed_at")),
            rs.getString("error_code"),
            rs.getString("error_message")
    );

    private final JdbcClient jdbcClient;

    public CollectionJobJdbcRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void insertIgnore(long runId, long repositoryId, LocalDate businessDate, CollectionJobType type) {
        jdbcClient.sql("""
                        INSERT INTO collection_job (
                            collection_run_id, repository_id, job_type, business_date, status
                        ) VALUES (
                            :runId, :repositoryId, :jobType, :businessDate, 'READY'
                        )
                        ON CONFLICT (repository_id, business_date, job_type) DO NOTHING
                        """)
                .param("runId", runId)
                .param("repositoryId", repositoryId)
                .param("jobType", type.name())
                .param("businessDate", businessDate)
                .update();
    }

    public Optional<CollectionJob> claim(String workerId, Duration lease) {
        return jdbcClient.sql("""
                        WITH candidate AS (
                            SELECT id
                            FROM collection_job
                            WHERE (
                                status IN ('READY', 'RETRY')
                                OR (status = 'RUNNING' AND locked_until < NOW())
                            )
                              AND (next_attempt_at IS NULL OR next_attempt_at <= NOW())
                              AND (locked_until IS NULL OR locked_until < NOW())
                            ORDER BY created_at
                            FOR UPDATE SKIP LOCKED
                            LIMIT 1
                        )
                        UPDATE collection_job j
                        SET status = 'RUNNING',
                            locked_by = :workerId,
                            locked_until = NOW() + make_interval(secs => :leaseSeconds),
                            started_at = NOW(),
                            attempt = attempt + 1,
                            updated_at = NOW()
                        FROM candidate
                        WHERE j.id = candidate.id
                        RETURNING j.*
                        """)
                .param("workerId", workerId)
                .param("leaseSeconds", lease.toSeconds())
                .query(MAPPER)
                .optional();
    }

    public void releaseClaim(long id, CollectionJobStatus status) {
        jdbcClient.sql("""
                        UPDATE collection_job
                        SET status = :status,
                            locked_by = NULL,
                            locked_until = NULL,
                            started_at = NULL,
                            attempt = GREATEST(attempt - 1, 0),
                            next_attempt_at = NOW() + INTERVAL '5 seconds',
                            updated_at = NOW()
                        WHERE id = :id
                        """)
                .param("status", status.name())
                .param("id", id)
                .update();
    }

    public void markSuccess(long id) {
        jdbcClient.sql("""
                        UPDATE collection_job
                        SET status = 'SUCCESS',
                            locked_by = NULL,
                            locked_until = NULL,
                            completed_at = NOW(),
                            error_code = NULL,
                            error_message = NULL,
                            updated_at = NOW()
                        WHERE id = :id
                        """)
                .param("id", id)
                .update();
    }

    public void markRetry(long id, Instant nextAttemptAt, String errorCode, String errorMessage) {
        jdbcClient.sql("""
                        UPDATE collection_job
                        SET status = 'RETRY',
                            locked_by = NULL,
                            locked_until = NULL,
                            next_attempt_at = :nextAttemptAt,
                            error_code = :errorCode,
                            error_message = :errorMessage,
                            updated_at = NOW()
                        WHERE id = :id
                        """)
                .param("id", id)
                .param("nextAttemptAt", SqlTime.ts(nextAttemptAt))
                .param("errorCode", errorCode)
                .param("errorMessage", truncate(errorMessage))
                .update();
    }

    public void markFailed(long id, String errorCode, String errorMessage) {
        jdbcClient.sql("""
                        UPDATE collection_job
                        SET status = 'FAILED',
                            locked_by = NULL,
                            locked_until = NULL,
                            completed_at = NOW(),
                            error_code = :errorCode,
                            error_message = :errorMessage,
                            updated_at = NOW()
                        WHERE id = :id
                        """)
                .param("id", id)
                .param("errorCode", errorCode)
                .param("errorMessage", truncate(errorMessage))
                .update();
    }

    public List<CollectionJob> findByRun(long runId) {
        return jdbcClient.sql("SELECT * FROM collection_job WHERE collection_run_id = :runId ORDER BY job_type")
                .param("runId", runId)
                .query(MAPPER)
                .list();
    }

    public List<CollectionJob> findByRunIds(List<Long> runIds) {
        if (runIds == null || runIds.isEmpty()) {
            return List.of();
        }
        return jdbcClient.sql("""
                        SELECT * FROM collection_job
                        WHERE collection_run_id IN (:runIds)
                        ORDER BY collection_run_id, job_type
                        """)
                .param("runIds", runIds)
                .query(MAPPER)
                .list();
    }

    public int requeueFailed(long runId) {
        return jdbcClient.sql("""
                        UPDATE collection_job
                        SET status = 'READY',
                            locked_by = NULL,
                            locked_until = NULL,
                            next_attempt_at = NOW(),
                            updated_at = NOW()
                        WHERE collection_run_id = :runId
                          AND status = 'FAILED'
                        """)
                .param("runId", runId)
                .update();
    }

    public int requeueCompleted(long runId, CollectionJobType type) {
        return jdbcClient.sql("""
                        UPDATE collection_job
                        SET status = 'READY',
                            locked_by = NULL,
                            locked_until = NULL,
                            next_attempt_at = NOW(),
                            updated_at = NOW()
                        WHERE collection_run_id = :runId
                          AND job_type = :jobType
                          AND status IN ('SUCCESS', 'FAILED')
                        """)
                .param("runId", runId)
                .param("jobType", type.name())
                .update();
    }

    public int countByStatus(CollectionJobStatus status) {
        return jdbcClient.sql("SELECT COUNT(*) FROM collection_job WHERE status = :status")
                .param("status", status.name())
                .query(Integer.class)
                .single();
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > 2000 ? message.substring(0, 2000) : message;
    }
}
