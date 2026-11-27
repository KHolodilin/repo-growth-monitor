package com.kholodilin.repogrowth.collection.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persistence for collector node health probes and attempt reconciliation. */
@Repository
public class CollectorNodeMonitorJdbcRepository {

  private final JdbcTemplate jdbc;

  public CollectorNodeMonitorJdbcRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public boolean isEligible(String node, long freshnessMillis) {
    return !jdbc.queryForList(
            "SELECT node_key FROM collector_node_health WHERE node_key=? AND state='UP' AND last_success_at>clock_timestamp()-(?*interval '1 millisecond')",
            String.class,
            node,
            freshnessMillis)
        .isEmpty();
  }

  public List<Map<String, Object>> attemptsDue(long checkIntervalMillis, int batchSize) {
    return jdbc.queryForList(
        """
        SELECT a.*,t.public_id AS job_id,t.state AS task_state,n.node_key,n.pool,d.endpoint,d.deadline_at
        FROM collection_attempt a JOIN collection_task t ON t.current_attempt_id=a.public_id JOIN collection_request r ON r.id=t.request_id
        JOIN collector_node n ON n.id=a.node_id JOIN collector_dispatch d ON d.attempt_id=a.public_id
        WHERE a.state IN ('ASSIGNED','ACCEPTED','RUNNING') AND r.state='ACTIVE' AND (a.last_checked_at IS NULL OR a.last_checked_at<clock_timestamp()-(?*interval '1 millisecond'))
        ORDER BY a.last_checked_at NULLS FIRST,a.assigned_at LIMIT ?
        """,
        checkIntervalMillis,
        batchSize);
  }

  public void ensureHealthRow(String nodeKey) {
    jdbc.update(
        "INSERT INTO collector_node_health(node_key) VALUES (?) ON CONFLICT DO NOTHING", nodeKey);
  }

  public List<Long> claimHealthCheck(String nodeKey) {
    return jdbc.queryForList(
        "SELECT generation FROM collector_node_health WHERE node_key=? AND next_check_at<=clock_timestamp() AND (locked_until IS NULL OR locked_until<=clock_timestamp()) FOR UPDATE SKIP LOCKED",
        Long.class,
        nodeKey);
  }

  public void lockHealthCheck(long generation, String owner, long lockMillis, String nodeKey) {
    jdbc.update(
        "UPDATE collector_node_health SET generation=?,locked_by=?,locked_until=clock_timestamp()+(?*interval '1 millisecond') WHERE node_key=?",
        generation,
        owner,
        lockMillis,
        nodeKey);
  }

  public void recordHealthResult(
      boolean up, long nextCheckMillis, String nodeKey, String owner, long generation) {
    jdbc.update(
        "UPDATE collector_node_health SET state=?,consecutive_failures=CASE WHEN ? THEN 0 ELSE consecutive_failures+1 END,last_checked_at=clock_timestamp(),last_success_at=CASE WHEN ? THEN clock_timestamp() ELSE last_success_at END,next_check_at=clock_timestamp()+(?*interval '1 millisecond'),locked_by=NULL,locked_until=NULL WHERE node_key=? AND locked_by=? AND generation=?",
        up ? "UP" : "DOWN",
        up,
        up,
        nextCheckMillis,
        nodeKey,
        owner,
        generation);
  }

  public List<UUID> resultReceipts(UUID attemptId) {
    return jdbc.queryForList(
        "SELECT event_id FROM collector_result_receipt WHERE attempt_id=?", UUID.class, attemptId);
  }

  public void markResultUnavailable(UUID attemptId) {
    jdbc.update(
        "UPDATE collection_attempt SET last_collector_state='RESULT_UNAVAILABLE' WHERE public_id=?",
        attemptId);
  }

  public void markCheckedUnavailable(UUID attemptId) {
    jdbc.update(
        "UPDATE collection_attempt SET last_checked_at=clock_timestamp(),last_collector_state='RESULT_UNAVAILABLE' WHERE public_id=?",
        attemptId);
  }

  public void incrementStatusFailures(UUID attemptId) {
    jdbc.update(
        "UPDATE collection_attempt SET status_failures=status_failures+1,last_checked_at=clock_timestamp() WHERE public_id=?",
        attemptId);
  }

  public int statusFailures(UUID attemptId) {
    return jdbc.queryForObject(
        "SELECT status_failures FROM collection_attempt WHERE public_id=?", Integer.class, attemptId);
  }

  public List<Integer> consecutiveFailures(String nodeKey) {
    return jdbc.queryForList(
        "SELECT consecutive_failures FROM collector_node_health WHERE node_key=?",
        Integer.class,
        nodeKey);
  }

  public void lockRequestByJob(UUID jobId) {
    jdbc.queryForList(
        "SELECT r.id FROM collection_request r JOIN collection_task t ON t.request_id=r.id WHERE t.public_id=? FOR UPDATE OF r",
        Long.class,
        jobId);
  }

  public List<Map<String, Object>> lockTask(UUID jobId) {
    return jdbc.queryForList(
        "SELECT id,current_attempt_id,state FROM collection_task WHERE public_id=? FOR UPDATE",
        jobId);
  }

  public List<UUID> lockAttemptCollectorJob(UUID attemptId) {
    return jdbc.queryForList(
        "SELECT collector_job_id FROM collection_attempt WHERE public_id=? FOR UPDATE",
        UUID.class,
        attemptId);
  }

  public void rememberProgress(
      UUID collectorJobId, Instant progress, String collectorState, UUID attemptId) {
    jdbc.update(
        "UPDATE collection_attempt SET collector_job_id=?,state=CASE WHEN state='ASSIGNED' THEN 'ACCEPTED' ELSE state END,last_checked_at=clock_timestamp(),status_failures=CASE WHEN last_progress_at IS NULL OR last_progress_at<? THEN 0 ELSE status_failures END,last_progress_at=?,last_collector_state=? WHERE public_id=?",
        collectorJobId,
        Timestamp.from(progress),
        Timestamp.from(progress),
        collectorState,
        attemptId);
  }

  public void acceptReadyTask(UUID jobId) {
    jdbc.update(
        "UPDATE collection_task SET state='ACCEPTED' WHERE public_id=? AND state='READY'", jobId);
  }

  public void ackDispatch(UUID attemptId) {
    jdbc.update(
        "UPDATE collector_dispatch SET state='ACKED',locked_by=NULL,locked_until=NULL,acknowledged_at=coalesce(acknowledged_at,clock_timestamp()) WHERE attempt_id=?",
        attemptId);
  }

  public List<Map<String, Object>> nodeHealthRows() {
    return jdbc.queryForList(
        "SELECT node_key,state,consecutive_failures,last_checked_at,last_success_at FROM collector_node_health ORDER BY node_key");
  }

  public List<Map<String, Object>> activeAttemptSummaries() {
    return jdbc.queryForList(
        "SELECT n.node_key,a.state,a.last_collector_state,count(*) AS count,max(extract(epoch FROM clock_timestamp()-a.assigned_at)) AS oldest_age_seconds FROM collection_attempt a JOIN collector_node n ON n.id=a.node_id WHERE a.state IN ('ASSIGNED','ACCEPTED','RUNNING') GROUP BY n.node_key,a.state,a.last_collector_state ORDER BY n.node_key,a.state");
  }
}
