package com.kholodilin.repogrowth.collection.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persistence for atomic collection-attempt reassignment. */
@Repository
public class AssignmentRecoveryJdbcRepository {

  private final JdbcTemplate jdbc;

  public AssignmentRecoveryJdbcRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void lockRequestByJob(UUID jobId) {
    jdbc.queryForList(
        "SELECT r.id FROM collection_request r JOIN collection_task t ON t.request_id=r.id WHERE t.public_id=? FOR UPDATE OF r",
        Long.class,
        jobId);
  }

  public List<Map<String, Object>> lockActiveTask(UUID jobId) {
    return jdbc.queryForList(
        "SELECT t.* FROM collection_task t JOIN collection_request r ON r.id=t.request_id WHERE t.public_id=? AND r.state='ACTIVE' FOR UPDATE OF t",
        jobId);
  }

  public Map<String, Object> lockAttemptDispatch(UUID attemptId) {
    return jdbc.queryForMap(
        "SELECT a.*,n.node_key,n.pool,d.payload FROM collection_attempt a JOIN collector_node n ON n.id=a.node_id JOIN collector_dispatch d ON d.attempt_id=a.public_id WHERE a.public_id=? FOR UPDATE OF a,d",
        attemptId);
  }

  public Instant clockTimestamp() {
    return jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
  }

  public void failAttempt(UUID attemptId) {
    jdbc.update(
        "UPDATE collection_attempt SET state='FAILED',completed_at=clock_timestamp() WHERE public_id=?",
        attemptId);
  }

  public void failTask(Object taskId) {
    jdbc.update(
        "UPDATE collection_task SET state='FAILED',completed_at=clock_timestamp() WHERE id=?",
        taskId);
  }

  public void blockDispatch(UUID attemptId, String lastError) {
    jdbc.update(
        "UPDATE collector_dispatch SET state='BLOCKED',last_error=?,locked_by=NULL,locked_until=NULL WHERE attempt_id=?",
        lastError,
        attemptId);
  }

  public void failRequestIfIdle(Object requestId) {
    jdbc.update(
        "UPDATE collection_request r SET state='FAILED',completed_at=clock_timestamp() WHERE r.id=? AND NOT EXISTS(SELECT 1 FROM collection_task t WHERE t.request_id=r.id AND t.state IN ('READY','ACCEPTED','RUNNING','RETRY'))",
        requestId);
  }

  public Long upsertCollectorNode(String key, String pool, String endpoint) {
    return jdbc.queryForObject(
        "INSERT INTO collector_node(node_key,pool,endpoint,enabled) VALUES (?,?,?,true) ON CONFLICT(node_key) DO UPDATE SET pool=excluded.pool,endpoint=excluded.endpoint,enabled=true RETURNING id",
        Long.class,
        key,
        pool,
        endpoint);
  }

  public void supersedeAttempt(UUID attemptId, Instant completedAt) {
    jdbc.update(
        "UPDATE collection_attempt SET state='SUPERSEDED',completed_at=? WHERE public_id=?",
        Timestamp.from(completedAt),
        attemptId);
  }

  public void blockDispatchSuperseded(UUID attemptId, String lastError) {
    jdbc.update(
        "UPDATE collector_dispatch SET state='BLOCKED',locked_by=NULL,locked_until=NULL,last_error=? WHERE attempt_id=?",
        lastError,
        attemptId);
  }

  public void insertAttempt(UUID attemptId, Object taskId, int attemptNo, long nodeId) {
    jdbc.update(
        "INSERT INTO collection_attempt(public_id,task_id,attempt_no,node_id) VALUES (?,?,?,?)",
        attemptId,
        taskId,
        attemptNo,
        nodeId);
  }

  public void setCurrentAttemptReady(UUID attemptId, Object taskId) {
    jdbc.update(
        "UPDATE collection_task SET current_attempt_id=?,state='READY',completed_at=NULL WHERE id=?",
        attemptId,
        taskId);
  }

  public void insertDispatch(UUID attemptId, String endpoint, String payload, Instant deadline) {
    jdbc.update(
        "INSERT INTO collector_dispatch(attempt_id,endpoint,payload,deadline_at) VALUES (?,?,?::jsonb,?)",
        attemptId,
        endpoint,
        payload,
        Timestamp.from(deadline));
  }
}
