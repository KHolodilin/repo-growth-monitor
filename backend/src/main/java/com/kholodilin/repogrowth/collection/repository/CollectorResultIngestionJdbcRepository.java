package com.kholodilin.repogrowth.collection.repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persistence for collector result receipts and task/attempt completion. */
@Repository
public class CollectorResultIngestionJdbcRepository {

  private final JdbcTemplate jdbc;

  public CollectorResultIngestionJdbcRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<Long> lockRequestByJob(UUID jobId) {
    return jdbc.queryForList(
        "SELECT r.id FROM collection_request r JOIN collection_task t ON t.request_id=r.id WHERE t.public_id=? FOR UPDATE OF r",
        Long.class,
        jobId);
  }

  public List<Map<String, Object>> lockTask(UUID jobId) {
    return jdbc.queryForList("SELECT * FROM collection_task WHERE public_id=? FOR UPDATE", jobId);
  }

  public List<Map<String, Object>> lockAttempt(UUID attemptId, Object taskId) {
    return jdbc.queryForList(
        "SELECT a.*,n.node_key,d.payload FROM collection_attempt a JOIN collector_node n ON n.id=a.node_id JOIN collector_dispatch d ON d.attempt_id=a.public_id WHERE a.public_id=? AND a.task_id=? FOR UPDATE OF a",
        attemptId,
        taskId);
  }

  public List<Map<String, Object>> lockReceipts(UUID attemptId, UUID eventId, UUID resultId) {
    return jdbc.queryForList(
        "SELECT * FROM collector_result_receipt WHERE attempt_id=? OR event_id=? OR result_id=? FOR UPDATE",
        attemptId,
        eventId,
        resultId);
  }

  public void completeTask(String state, Object taskId, UUID attemptId) {
    jdbc.update(
        "UPDATE collection_task SET state=?,completed_at=clock_timestamp() WHERE id=? AND current_attempt_id=?",
        state,
        taskId,
        attemptId);
  }

  public void completeAttempt(String state, UUID collectorJobId, UUID attemptId) {
    jdbc.update(
        "UPDATE collection_attempt SET state=?,collector_job_id=coalesce(collector_job_id,?),completed_at=clock_timestamp() WHERE public_id=?",
        state,
        collectorJobId,
        attemptId);
  }

  public void insertReceipt(
      UUID eventId,
      UUID resultId,
      UUID attemptId,
      Object taskId,
      Object nodeId,
      String payloadHash,
      String outcome) {
    jdbc.update(
        "INSERT INTO collector_result_receipt(event_id,result_id,attempt_id,task_id,node_id,payload_hash,outcome) VALUES (?,?,?,?,?,?,?)",
        eventId,
        resultId,
        attemptId,
        taskId,
        nodeId,
        payloadHash,
        outcome);
  }

  public void completeRequestIfIdle(Object requestId) {
    jdbc.update(
        """
        UPDATE collection_request r SET state=CASE WHEN EXISTS(SELECT 1 FROM collection_task t WHERE t.request_id=r.id AND t.state='FAILED') THEN 'FAILED' WHEN EXISTS(SELECT 1 FROM collection_task t WHERE t.request_id=r.id AND t.state='CANCELLED') THEN 'CANCELLED' ELSE 'SUCCEEDED' END,completed_at=clock_timestamp()
        WHERE r.id=? AND r.state='ACTIVE' AND NOT EXISTS(SELECT 1 FROM collection_task t WHERE t.request_id=r.id AND t.state IN ('READY','ACCEPTED','RUNNING','RETRY'))
        """,
        requestId);
  }
}
