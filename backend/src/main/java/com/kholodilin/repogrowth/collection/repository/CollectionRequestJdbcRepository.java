package com.kholodilin.repogrowth.collection.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persistence for collection requests, tasks, attempts, and dispatch envelopes. */
@Repository
public class CollectionRequestJdbcRepository {

  private final JdbcTemplate jdbc;

  public CollectionRequestJdbcRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<Long> discoverySeedRepositoryIds() {
    return jdbc.queryForList(
        "SELECT min(r.id) FROM repository r JOIN repository_credential b ON b.repository_id=r.id JOIN github_credential c ON c.id=b.credential_id AND NOT c.revoked GROUP BY r.owner_id,b.credential_id ORDER BY min(r.id)",
        Long.class);
  }

  public List<String> activeCredentialRefs() {
    return jdbc.queryForList(
        "SELECT ref FROM github_credential WHERE NOT revoked ORDER BY ref", String.class);
  }

  public void advisoryLock(Object key) {
    jdbc.queryForObject("SELECT pg_advisory_xact_lock(?)", Object.class, key);
  }

  public List<Map<String, Object>> activeCredentialDiscovery(String credentialRef) {
    return jdbc.queryForList(
        "SELECT id,public_id,state FROM collection_request WHERE repository_id IS NULL AND credential_ref=? AND state='ACTIVE' ORDER BY id DESC LIMIT 1 FOR UPDATE",
        credentialRef);
  }

  public long countDiscoverTasks(long requestId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM collection_task WHERE request_id=? AND task_type='DISCOVER_REPOSITORIES'",
        Long.class,
        requestId);
  }

  public Long insertCredentialRequest(UUID publicId, LocalDate date, String credentialRef) {
    return jdbc.queryForObject(
        "INSERT INTO collection_request(public_id,repository_id,trigger_type,trigger_key,business_date,credential_ref) VALUES (?,NULL,'MANUAL',NULL,?,?) RETURNING id",
        Long.class,
        publicId,
        date,
        credentialRef);
  }

  public Long insertDiscoverTask(
      UUID taskId,
      long requestId,
      String targetKey,
      String parametersJson,
      String frozenJson,
      long revision,
      UUID attemptId,
      UUID authorizationContext,
      String credentialRef) {
    return jdbc.queryForObject(
        "INSERT INTO collection_task(public_id,request_id,repository_id,task_type,target_key,parameters,configuration_snapshot,configuration_revision,current_attempt_id,authorization_context,credential_ref) VALUES (?,?,NULL,'DISCOVER_REPOSITORIES',?,?::jsonb,?::jsonb,?,?,?::uuid,?) RETURNING id",
        Long.class,
        taskId,
        requestId,
        targetKey,
        parametersJson,
        frozenJson,
        revision,
        attemptId,
        authorizationContext,
        credentialRef);
  }

  public void insertAttempt(UUID attemptId, long taskId, long nodeId) {
    jdbc.update(
        "INSERT INTO collection_attempt(public_id,task_id,attempt_no,node_id) VALUES (?,?,1,?)",
        attemptId,
        taskId,
        nodeId);
  }

  public void insertDispatch(UUID attemptId, String endpoint, String payload, Instant deadline) {
    jdbc.update(
        "INSERT INTO collector_dispatch(attempt_id,endpoint,payload,deadline_at) VALUES (?,?,?::jsonb,?)",
        attemptId,
        endpoint,
        payload,
        Timestamp.from(deadline));
  }

  public void insertDispatchForNode(UUID attemptId, long nodeId, String payload, Instant deadline) {
    jdbc.update(
        "INSERT INTO collector_dispatch(attempt_id,endpoint,payload,deadline_at) VALUES (?,(SELECT endpoint FROM collector_node WHERE id=?),?::jsonb,?)",
        attemptId,
        nodeId,
        payload,
        Timestamp.from(deadline));
  }

  public String firstDispatchPayload(long requestId) {
    return jdbc.queryForObject(
        "SELECT d.payload::text FROM collection_task t JOIN collector_dispatch d ON d.attempt_id=t.current_attempt_id WHERE t.request_id=? ORDER BY t.id LIMIT 1",
        String.class,
        requestId);
  }

  public long nodeIdForAttempt(UUID attemptId) {
    return jdbc.queryForObject(
        "SELECT node_id FROM collection_attempt WHERE public_id=?", Long.class, attemptId);
  }

  public Long insertDiscoverTaskWithRepository(
      UUID taskId,
      long requestId,
      long repositoryId,
      long revision,
      UUID attemptId,
      UUID authorizationContext,
      String credentialRef) {
    return jdbc.queryForObject(
        "INSERT INTO collection_task(public_id,request_id,repository_id,task_type,target_key,parameters,configuration_snapshot,configuration_revision,current_attempt_id,authorization_context,credential_ref) VALUES (?,?,?,'DISCOVER_REPOSITORIES','repository','{}','{}',?,?,?::uuid,?) RETURNING id",
        Long.class,
        taskId,
        requestId,
        repositoryId,
        revision,
        attemptId,
        authorizationContext,
        credentialRef);
  }

  public String nodeEndpoint(long nodeId) {
    return jdbc.queryForObject(
        "SELECT endpoint FROM collector_node WHERE id=?", String.class, nodeId);
  }

  public List<Map<String, Object>> repositoryWithOwner(long repositoryId) {
    return jdbc.queryForList(
        "SELECT r.*,o.login AS owner_login FROM repository r JOIN github_owner o ON o.id=r.owner_id WHERE r.id=?",
        repositoryId);
  }

  public List<Map<String, Object>> existingRequest(long repositoryId, String triggerKey) {
    return jdbc.queryForList(
        "SELECT id,public_id,business_date,state,trigger_key FROM collection_request WHERE repository_id=? AND (state='ACTIVE' OR (?::text IS NOT NULL AND trigger_key=?)) ORDER BY (trigger_key=?::text) DESC NULLS LAST,id DESC LIMIT 1",
        repositoryId,
        triggerKey,
        triggerKey,
        triggerKey);
  }

  public String lockRequestState(long requestId) {
    return jdbc.queryForObject(
        "SELECT state FROM collection_request WHERE id=? FOR UPDATE", String.class, requestId);
  }

  public Long insertRepositoryRequest(
      UUID publicId, long repositoryId, String triggerType, String triggerKey, LocalDate date) {
    return jdbc.queryForObject(
        "INSERT INTO collection_request(public_id,repository_id,trigger_type,trigger_key,business_date) VALUES (?,?,?,?,?) RETURNING id",
        Long.class,
        publicId,
        repositoryId,
        triggerType,
        triggerKey,
        date);
  }

  public Long insertTask(
      UUID taskId,
      long requestId,
      long repositoryId,
      String taskType,
      String targetKey,
      String parametersJson,
      String frozenJson,
      long revision,
      UUID attemptId) {
    return jdbc.queryForObject(
        "INSERT INTO collection_task(public_id,request_id,repository_id,task_type,target_key,parameters,configuration_snapshot,configuration_revision,current_attempt_id) VALUES (?,?,?,?,?,?::jsonb,?::jsonb,?,?) RETURNING id",
        Long.class,
        taskId,
        requestId,
        repositoryId,
        taskType,
        targetKey,
        parametersJson,
        frozenJson,
        revision,
        attemptId);
  }

  public void setTaskAuthorization(UUID context, String credentialRef, long taskId) {
    jdbc.update(
        "UPDATE collection_task SET authorization_context=?::uuid,credential_ref=? WHERE id=?",
        context,
        credentialRef,
        taskId);
  }

  public Long upsertCollectorNode(String key, String pool, String endpoint) {
    return jdbc.queryForObject(
        "INSERT INTO collector_node(node_key,pool,endpoint,enabled) VALUES (?,?,?,true) ON CONFLICT(node_key) DO UPDATE SET pool=excluded.pool,endpoint=excluded.endpoint,enabled=true,updated_at=clock_timestamp() RETURNING id",
        Long.class,
        key,
        pool,
        endpoint);
  }

  public long countTasks(long requestId, String taskType, String targetKey) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM collection_task WHERE request_id=? AND task_type=? AND target_key=?",
        Long.class,
        requestId,
        taskType,
        targetKey);
  }

  public List<Map<String, Object>> activeRequest(long repositoryId) {
    return jdbc.queryForList(
        "SELECT id,business_date FROM collection_request WHERE repository_id=? AND state='ACTIVE' ORDER BY id DESC LIMIT 1",
        repositoryId);
  }

  public List<LocalDate> incompatibleSearchTipDates(
      long repositoryId, LocalDate from, LocalDate to) {
    return jdbc.query(
        """
        SELECT DISTINCT sr.business_date
        FROM search_run sr
        JOIN search_query sq ON sq.id=sr.search_query_id AND sq.repository_id=sr.repository_id
        WHERE sq.repository_id=?
          AND sq.enabled
          AND sr.business_date>=?
          AND sr.business_date<=?
          AND sr.snapshot_at IS NOT NULL
          AND COALESCE(NULLIF(sr.sort_mode,''),'stars')<>'stars'
          AND EXISTS(
            SELECT 1 FROM search_run stars
            WHERE stars.search_query_id=sq.id
              AND stars.snapshot_at IS NOT NULL
              AND COALESCE(NULLIF(stars.sort_mode,''),'stars')='stars'
          )
        ORDER BY 1
        """,
        (rs, i) -> rs.getObject(1, LocalDate.class),
        repositoryId,
        from,
        to);
  }

  public boolean needsStarsRepair(long queryId, LocalDate tip) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            """
            SELECT EXISTS(
              SELECT 1 FROM search_run
              WHERE search_query_id=? AND business_date=? AND snapshot_at IS NOT NULL
                AND COALESCE(NULLIF(sort_mode,''),'stars')<>'stars'
            )
            """,
            Boolean.class,
            queryId,
            tip));
  }

  public List<Long> searchTaskRequestIds(long queryId) {
    return jdbc.queryForList(
        "SELECT DISTINCT request_id FROM collection_task WHERE task_type='SEARCH_QUERY' AND (target_key=? OR target_key LIKE ?) ORDER BY request_id",
        Long.class,
        "query:" + queryId,
        "query:" + queryId + "@%");
  }

  public void lockRequest(long requestId) {
    jdbc.queryForList(
        "SELECT id FROM collection_request WHERE id=? FOR UPDATE", Long.class, requestId);
  }

  public List<Map<String, Object>> openSearchTasks(long requestId, long queryId) {
    return jdbc.queryForList(
        "SELECT id,current_attempt_id FROM collection_task WHERE request_id=? AND task_type='SEARCH_QUERY' AND (target_key=? OR target_key LIKE ?) AND state IN ('READY','ACCEPTED','RUNNING','RETRY') FOR UPDATE",
        requestId,
        "query:" + queryId,
        "query:" + queryId + "@%");
  }

  public void cancelTask(Object taskId) {
    jdbc.update(
        "UPDATE collection_task SET state='CANCELLED',completed_at=clock_timestamp() WHERE id=?",
        taskId);
  }

  public void cancelAttempt(Object attemptId) {
    jdbc.update(
        "UPDATE collection_attempt SET state='CANCELLED',completed_at=clock_timestamp() WHERE public_id=?",
        attemptId);
  }

  public void blockDispatch(Object attemptId, String lastError) {
    jdbc.update(
        "UPDATE collector_dispatch SET state='BLOCKED',last_error=?,locked_by=NULL,locked_until=NULL WHERE attempt_id=?",
        lastError,
        attemptId);
  }

  public void cancelRequestIfIdle(long requestId) {
    jdbc.update(
        "UPDATE collection_request r SET state='CANCELLED',completed_at=clock_timestamp() WHERE r.id=? AND r.state='ACTIVE' AND NOT EXISTS(SELECT 1 FROM collection_task t WHERE t.request_id=r.id AND t.state IN ('READY','ACCEPTED','RUNNING','RETRY'))",
        requestId);
  }

  public List<UUID> taskPublicIds(long requestId) {
    return jdbc.queryForList(
        "SELECT public_id FROM collection_task WHERE request_id=? ORDER BY id",
        UUID.class,
        requestId);
  }

  public boolean searchPublic(long queryId) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT public_enabled FROM search_query WHERE id=?", Boolean.class, queryId));
  }

  public boolean topicsPublic(long repositoryId) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT public_topics_enabled FROM repository WHERE id=?",
            Boolean.class,
            repositoryId));
  }
}
