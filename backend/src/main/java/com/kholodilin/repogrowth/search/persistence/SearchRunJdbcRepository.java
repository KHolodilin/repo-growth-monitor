package com.kholodilin.repogrowth.search.persistence;

import com.kholodilin.repogrowth.common.persistence.SqlTime;
import com.kholodilin.repogrowth.search.domain.SearchRun;
import com.kholodilin.repogrowth.search.domain.SearchRunStatus;
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
public class SearchRunJdbcRepository {

    static final RowMapper<SearchRun> MAPPER = (rs, rowNum) -> new SearchRun(
            rs.getLong("id"),
            rs.getLong("search_query_id"),
            rs.getLong("repository_id"),
            rs.getObject("business_date", LocalDate.class),
            SearchRunStatus.valueOf(rs.getString("status")),
            rs.getInt("attempt"),
            toInstant(rs.getTimestamp("next_attempt_at")),
            rs.getString("locked_by"),
            toInstant(rs.getTimestamp("locked_until")),
            toInstant(rs.getTimestamp("started_at")),
            toInstant(rs.getTimestamp("completed_at")),
            (Integer) rs.getObject("total_count"),
            (Integer) rs.getObject("tracked_repository_position"),
            rs.getString("error_code"),
            rs.getString("error_message")
    );

    private final JdbcClient jdbcClient;

    public SearchRunJdbcRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void insertIgnore(long searchQueryId, long repositoryId, LocalDate businessDate) {
        jdbcClient.sql("""
                        INSERT INTO search_run (search_query_id, repository_id, business_date, status)
                        VALUES (:searchQueryId, :repositoryId, :businessDate, 'READY')
                        ON CONFLICT (search_query_id, business_date) DO NOTHING
                        """)
                .param("searchQueryId", searchQueryId)
                .param("repositoryId", repositoryId)
                .param("businessDate", businessDate)
                .update();
    }

    public Optional<SearchRun> find(long searchQueryId, LocalDate businessDate) {
        return jdbcClient.sql("""
                        SELECT * FROM search_run
                        WHERE search_query_id = :searchQueryId AND business_date = :businessDate
                        """)
                .param("searchQueryId", searchQueryId)
                .param("businessDate", businessDate)
                .query(MAPPER)
                .optional();
    }

    public Optional<SearchRun> findById(long id) {
        return jdbcClient.sql("SELECT * FROM search_run WHERE id = :id")
                .param("id", id)
                .query(MAPPER)
                .optional();
    }

    public Optional<SearchRun> claim(String workerId, Duration lease) {
        return jdbcClient.sql("""
                        WITH candidate AS (
                            SELECT id
                            FROM search_run
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
                        UPDATE search_run j
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
                        UPDATE search_run
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
                        UPDATE search_run
                        SET status = 'SUCCESS',
                            locked_by = NULL,
                            locked_until = NULL,
                            completed_at = NOW(),
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

    public void markRetry(long id, Instant nextAttemptAt, String errorCode, String errorMessage) {
        jdbcClient.sql("""
                        UPDATE search_run
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
                        UPDATE search_run
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

    public List<SearchRun> history(long searchQueryId) {
        return jdbcClient.sql("""
                        SELECT * FROM search_run
                        WHERE search_query_id = :searchQueryId
                        ORDER BY business_date
                        """)
                .param("searchQueryId", searchQueryId)
                .query(MAPPER)
                .list();
    }

    public Optional<SearchRun> previousSuccessful(long searchQueryId, LocalDate before) {
        return jdbcClient.sql("""
                        SELECT * FROM search_run
                        WHERE search_query_id = :searchQueryId
                          AND status = 'SUCCESS'
                          AND business_date < :before
                        ORDER BY business_date DESC
                        LIMIT 1
                        """)
                .param("searchQueryId", searchQueryId)
                .param("before", before)
                .query(MAPPER)
                .optional();
    }

    public Optional<SearchRun> latestSuccessful(long searchQueryId) {
        return jdbcClient.sql("""
                        SELECT * FROM search_run
                        WHERE search_query_id = :searchQueryId AND status = 'SUCCESS'
                        ORDER BY business_date DESC
                        LIMIT 1
                        """)
                .param("searchQueryId", searchQueryId)
                .query(MAPPER)
                .optional();
    }

    public int countByStatus(SearchRunStatus status) {
        return jdbcClient.sql("SELECT COUNT(*) FROM search_run WHERE status = :status")
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
