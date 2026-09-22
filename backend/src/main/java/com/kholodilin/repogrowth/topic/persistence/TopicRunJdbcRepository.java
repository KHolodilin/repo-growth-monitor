package com.kholodilin.repogrowth.topic.persistence;

import com.kholodilin.repogrowth.common.persistence.SqlTime;
import com.kholodilin.repogrowth.search.domain.SearchRunStatus;
import com.kholodilin.repogrowth.topic.domain.TopicRun;
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
public class TopicRunJdbcRepository {

    static final RowMapper<TopicRun> MAPPER = (rs, rowNum) -> new TopicRun(
            rs.getLong("id"),
            rs.getLong("topic_watch_id"),
            rs.getLong("repository_id"),
            rs.getObject("business_date", LocalDate.class),
            SearchRunStatus.valueOf(rs.getString("status")),
            rs.getInt("attempt"),
            toInstant(rs.getTimestamp("next_attempt_at")),
            rs.getString("locked_by"),
            toInstant(rs.getTimestamp("locked_until")),
            toInstant(rs.getTimestamp("started_at")),
            toInstant(rs.getTimestamp("completed_at")),
            toInstant(rs.getTimestamp("snapshot_at")),
            (Integer) rs.getObject("total_count"),
            (Integer) rs.getObject("tracked_repository_position"),
            rs.getString("error_code"),
            rs.getString("error_message")
    );

    private final JdbcClient jdbcClient;

    public TopicRunJdbcRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void insertIgnore(long topicWatchId, long repositoryId, LocalDate businessDate) {
        jdbcClient.sql("""
                        INSERT INTO topic_run (topic_watch_id, repository_id, business_date, status)
                        VALUES (:topicWatchId, :repositoryId, :businessDate, 'READY')
                        ON CONFLICT (topic_watch_id, business_date) DO NOTHING
                        """)
                .param("topicWatchId", topicWatchId)
                .param("repositoryId", repositoryId)
                .param("businessDate", businessDate)
                .update();
    }

    public int markMissedDays(LocalDate businessDate, int lookbackDays) {
        return jdbcClient.sql("""
                        INSERT INTO topic_run (topic_watch_id, repository_id, business_date, status)
                        SELECT w.id, w.repository_id, day::date, 'MISSED'
                        FROM topic_watch w
                        JOIN repository r ON r.id = w.repository_id
                        CROSS JOIN LATERAL generate_series(
                                GREATEST(w.created_at::date, :fromDate),
                                :businessDate - 1,
                                INTERVAL '1 day') day
                        WHERE w.enabled = TRUE
                          AND r.account_accessible = TRUE
                        ON CONFLICT (topic_watch_id, business_date) DO NOTHING
                        """)
                .param("fromDate", businessDate.minusDays(lookbackDays))
                .param("businessDate", businessDate)
                .update();
    }

    public Optional<TopicRun> find(long topicWatchId, LocalDate businessDate) {
        return jdbcClient.sql("""
                        SELECT * FROM topic_run
                        WHERE topic_watch_id = :topicWatchId AND business_date = :businessDate
                        """)
                .param("topicWatchId", topicWatchId)
                .param("businessDate", businessDate)
                .query(MAPPER)
                .optional();
    }

    public Optional<TopicRun> findById(long id) {
        return jdbcClient.sql("SELECT * FROM topic_run WHERE id = :id")
                .param("id", id)
                .query(MAPPER)
                .optional();
    }

    public Optional<TopicRun> claim(String workerId, Duration lease) {
        return jdbcClient.sql("""
                        WITH candidate AS (
                            SELECT id
                            FROM topic_run
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
                        UPDATE topic_run j
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

    public void releaseClaim(long id, SearchRunStatus status) {
        jdbcClient.sql("""
                        UPDATE topic_run
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

    public void markSuccess(long id, Integer totalCount, Integer position) {
        jdbcClient.sql("""
                        UPDATE topic_run
                        SET status = 'SUCCESS',
                            locked_by = NULL,
                            locked_until = NULL,
                            completed_at = NOW(),
                            snapshot_at = NOW(),
                            total_count = :totalCount,
                            tracked_repository_position = :position,
                            error_code = NULL,
                            error_message = NULL,
                            updated_at = NOW()
                        WHERE id = :id
                        """)
                .param("id", id)
                .param("totalCount", totalCount)
                .param("position", position)
                .update();
    }

    public void requeueCompleted(long id) {
        jdbcClient.sql("""
                        UPDATE topic_run
                        SET status = 'READY',
                            locked_by = NULL,
                            locked_until = NULL,
                            next_attempt_at = NOW(),
                            attempt = 0,
                            updated_at = NOW()
                        WHERE id = :id
                          AND status IN ('SUCCESS', 'FAILED')
                        """)
                .param("id", id)
                .update();
    }

    public void markRetry(long id, Instant nextAttemptAt, String errorCode, String errorMessage) {
        jdbcClient.sql("""
                        UPDATE topic_run
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
                        UPDATE topic_run
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

    public List<TopicRun> snapshots(long topicWatchId) {
        return jdbcClient.sql("""
                        SELECT * FROM topic_run
                        WHERE topic_watch_id = :topicWatchId
                          AND snapshot_at IS NOT NULL
                        ORDER BY business_date
                        """)
                .param("topicWatchId", topicWatchId)
                .query(MAPPER)
                .list();
    }

    public Optional<TopicRun> previousSnapshot(long topicWatchId, LocalDate before) {
        return jdbcClient.sql("""
                        SELECT * FROM topic_run
                        WHERE topic_watch_id = :topicWatchId
                          AND snapshot_at IS NOT NULL
                          AND business_date < :before
                        ORDER BY business_date DESC
                        LIMIT 1
                        """)
                .param("topicWatchId", topicWatchId)
                .param("before", before)
                .query(MAPPER)
                .optional();
    }

    public Optional<TopicRun> latest(long topicWatchId) {
        return jdbcClient.sql("""
                        SELECT * FROM topic_run
                        WHERE topic_watch_id = :topicWatchId
                          AND status <> 'MISSED'
                        ORDER BY business_date DESC, id DESC
                        LIMIT 1
                        """)
                .param("topicWatchId", topicWatchId)
                .query(MAPPER)
                .optional();
    }

    public List<LocalDate> missedDates(long topicWatchId) {
        return jdbcClient.sql("""
                        SELECT business_date FROM topic_run
                        WHERE topic_watch_id = :topicWatchId AND status = 'MISSED'
                        ORDER BY business_date
                        """)
                .param("topicWatchId", topicWatchId)
                .query(LocalDate.class)
                .list();
    }

    public Optional<TopicRun> latestSnapshot(long topicWatchId) {
        return jdbcClient.sql("""
                        SELECT * FROM topic_run
                        WHERE topic_watch_id = :topicWatchId AND snapshot_at IS NOT NULL
                        ORDER BY business_date DESC
                        LIMIT 1
                        """)
                .param("topicWatchId", topicWatchId)
                .query(MAPPER)
                .optional();
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
