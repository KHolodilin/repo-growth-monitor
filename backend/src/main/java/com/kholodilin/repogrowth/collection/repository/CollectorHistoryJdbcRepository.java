package com.kholodilin.repogrowth.collection.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persistence for collector snapshot headers, history mapping, and legacy run/job rows. */
@Repository
public class CollectorHistoryJdbcRepository {

  private final JdbcTemplate jdbc;

  public CollectorHistoryJdbcRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void upsertSnapshotHeader(
      long repositoryId, String family, LocalDate businessDate, Instant observedAt) {
    jdbc.update(
        "INSERT INTO collector_snapshot_header VALUES (?,?,?,?) ON CONFLICT(repository_id,family,business_date) DO UPDATE SET observed_at=excluded.observed_at",
        repositoryId,
        family,
        businessDate,
        Timestamp.from(observedAt));
  }

  public void insertHistoryMapping(long taskId, String jobType, long runId) {
    jdbc.update(
        "INSERT INTO collection_history_mapping VALUES (?,?,?) ON CONFLICT(task_id) DO NOTHING",
        taskId,
        jobType,
        runId);
  }

  public String targetKeyForTask(long taskId) {
    return jdbc.queryForObject(
        "SELECT target_key FROM collection_task WHERE id=?", String.class, taskId);
  }

  public long ensureLegacyRun(long repositoryId, LocalDate businessDate) {
    return jdbc.queryForObject(
        "INSERT INTO collection_run(repository_id,business_date,status,planned_jobs) VALUES (?,?,'RUNNING',5) ON CONFLICT(repository_id,business_date) DO UPDATE SET planned_jobs=greatest(collection_run.planned_jobs,5) RETURNING id",
        Long.class,
        repositoryId,
        businessDate);
  }

  public void upsertLegacyJob(
      long runId,
      long repositoryId,
      String jobType,
      LocalDate businessDate,
      String status,
      String errorCode,
      String errorMessage) {
    jdbc.update(
        "INSERT INTO collection_job(collection_run_id,repository_id,job_type,business_date,status,completed_at,error_code,error_message) VALUES (?,?,?,?,?,clock_timestamp(),?,?) ON CONFLICT(repository_id,business_date,job_type) DO UPDATE SET status=excluded.status,completed_at=excluded.completed_at,error_code=excluded.error_code,error_message=excluded.error_message",
        runId,
        repositoryId,
        jobType,
        businessDate,
        status,
        errorCode,
        errorMessage);
  }

  public void refreshLegacyRun(long runId) {
    jdbc.update(
        "UPDATE collection_run r SET successful_jobs=(SELECT count(*) FROM collection_job j WHERE j.collection_run_id=r.id AND j.status='SUCCESS'),failed_jobs=(SELECT count(*) FROM collection_job j WHERE j.collection_run_id=r.id AND j.status='FAILED'),status=CASE WHEN (SELECT count(*) FROM collection_job j WHERE j.collection_run_id=r.id AND j.status IN ('SUCCESS','FAILED'))>=r.planned_jobs THEN CASE WHEN EXISTS(SELECT 1 FROM collection_job j WHERE j.collection_run_id=r.id AND j.status='FAILED') THEN 'PARTIAL' ELSE 'SUCCESS' END ELSE 'RUNNING' END,completed_at=CASE WHEN (SELECT count(*) FROM collection_job j WHERE j.collection_run_id=r.id AND j.status IN ('SUCCESS','FAILED'))>=r.planned_jobs THEN clock_timestamp() ELSE NULL END WHERE id=?",
        runId);
  }
}
