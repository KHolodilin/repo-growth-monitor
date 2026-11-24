package com.kholodilin.repogrowth.collection.persistence;

import com.kholodilin.repogrowth.collection.domain.CollectionJob;
import com.kholodilin.repogrowth.collection.domain.CollectionJobStatus;
import com.kholodilin.repogrowth.collection.domain.CollectionJobType;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class CollectionJobJdbcRepository {

  static final RowMapper<CollectionJob> MAPPER =
      (rs, rowNum) ->
          new CollectionJob(
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
              rs.getString("error_message"));

  private final JdbcClient jdbcClient;

  public CollectionJobJdbcRepository(JdbcClient jdbcClient) {
    this.jdbcClient = jdbcClient;
  }

  public void insertIgnore(
      long runId, long repositoryId, LocalDate businessDate, CollectionJobType type) {
    jdbcClient
        .sql(
            """
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

  public void markSuccess(long id) {
    jdbcClient
        .sql(
            """
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

  public void markFailed(long id, String errorCode, String errorMessage) {
    jdbcClient
        .sql(
            """
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
    return jdbcClient
        .sql("SELECT * FROM collection_job WHERE collection_run_id = :runId ORDER BY job_type")
        .param("runId", runId)
        .query(MAPPER)
        .list();
  }

  public List<CollectionJob> findByRunIds(List<Long> runIds) {
    if (runIds == null || runIds.isEmpty()) {
      return List.of();
    }
    return jdbcClient
        .sql(
            """
            SELECT * FROM collection_job
            WHERE collection_run_id IN (:runIds)
            ORDER BY collection_run_id, job_type
            """)
        .param("runIds", runIds)
        .query(MAPPER)
        .list();
  }

  public int countByStatus(CollectionJobStatus status) {
    return jdbcClient
        .sql("SELECT COUNT(*) FROM collection_job WHERE status = :status")
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
