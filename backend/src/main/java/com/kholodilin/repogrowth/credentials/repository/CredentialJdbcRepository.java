package com.kholodilin.repogrowth.credentials.repository;

import com.kholodilin.repogrowth.credentials.service.CredentialEncryption;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persistence for credential vault rows, bindings, discovery scopes, and lease resolution. */
@Repository
public class CredentialJdbcRepository {

  private final JdbcTemplate jdbc;

  public CredentialJdbcRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public boolean hasActiveCredential() {
    try {
      return !jdbc.queryForList("SELECT 1 FROM github_credential WHERE NOT revoked LIMIT 1")
          .isEmpty();
    } catch (DataAccessException e) {
      return false;
    }
  }

  public void ensureCredentialRow(String ref) {
    jdbc.update(
        "INSERT INTO github_credential(ref,current_version) VALUES (?,0) ON CONFLICT(ref) DO NOTHING",
        ref);
  }

  public Map<String, Object> lockCredentialByRef(String ref) {
    return jdbc.queryForMap(
        "SELECT id,current_version,quota_identity FROM github_credential WHERE ref=? FOR UPDATE",
        ref);
  }

  public void insertCredentialVersion(
      long credentialId,
      long version,
      CredentialEncryption.Encrypted encrypted,
      Instant expiresAt) {
    jdbc.update(
        "INSERT INTO github_credential_version(credential_id,version,key_id,nonce,ciphertext,expires_at) VALUES (?,?,?,?,?,?)",
        credentialId,
        version,
        encrypted.keyId(),
        encrypted.nonce(),
        encrypted.ciphertext(),
        expiresAt == null ? null : Timestamp.from(expiresAt));
  }

  public void activateCredentialVersion(long credentialId, long version) {
    jdbc.update(
        "UPDATE github_credential SET current_version=?,revoked=false,updated_at=clock_timestamp() WHERE id=?",
        version,
        credentialId);
  }

  public void updateQuotaIdentity(long credentialId, String identity) {
    jdbc.update("UPDATE github_credential SET quota_identity=? WHERE id=?", identity, credentialId);
  }

  public boolean repositoryExistsForUpdate(long repositoryId) {
    return !jdbc.queryForList(
            "SELECT id FROM repository WHERE id=? FOR UPDATE", Long.class, repositoryId)
        .isEmpty();
  }

  public void lockRepositoryForUpdate(long repositoryId) {
    jdbc.queryForList("SELECT id FROM repository WHERE id=? FOR UPDATE", Long.class, repositoryId);
  }

  public Optional<Long> activeCredentialIdByRef(String ref) {
    var rows =
        jdbc.queryForList(
            "SELECT id FROM github_credential WHERE ref=? AND NOT revoked FOR SHARE",
            Long.class,
            ref);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }

  public void upsertRepositoryBinding(long repositoryId, long credentialId, UUID context) {
    jdbc.update(
        "INSERT INTO repository_credential(repository_id,credential_id,authorization_context) VALUES (?,?,?) ON CONFLICT(repository_id) DO UPDATE SET credential_id=excluded.credential_id,authorization_context=excluded.authorization_context,updated_at=clock_timestamp()",
        repositoryId,
        credentialId,
        context);
  }

  public boolean revokeByRef(String ref) {
    return jdbc.update(
            "UPDATE github_credential SET revoked=true,updated_at=clock_timestamp() WHERE ref=?",
            ref)
        == 1;
  }

  public long repositoryBindingCount(long repositoryId) {
    Long count =
        jdbc.queryForObject(
            "SELECT count(*) FROM repository_credential WHERE repository_id=?",
            Long.class,
            repositoryId);
    return count == null ? 0L : count;
  }

  public List<Map<String, Object>> activeRepositoryBinding(long repositoryId) {
    return jdbc.queryForList(
        "SELECT b.authorization_context,c.ref FROM repository_credential b JOIN github_credential c ON c.id=b.credential_id WHERE b.repository_id=? AND NOT c.revoked",
        repositoryId);
  }

  public Optional<Long> lockActiveCredentialIdByRef(String ref) {
    var rows =
        jdbc.queryForList(
            "SELECT id FROM github_credential WHERE ref=? AND NOT revoked FOR UPDATE",
            Long.class,
            ref);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }

  public List<Map<String, Object>> discoveryScopes(long credentialId) {
    return jdbc.queryForList(
        "SELECT authorization_context FROM credential_discovery_scope WHERE credential_id=?",
        credentialId);
  }

  public void insertDiscoveryScope(long credentialId, UUID context) {
    jdbc.update(
        "INSERT INTO credential_discovery_scope(credential_id,authorization_context) VALUES (?,?)",
        credentialId,
        context);
  }

  public void shareLockCollectionRequestForTask(String taskPublicId) {
    jdbc.queryForList(
        "SELECT r.id FROM collection_request r JOIN collection_task t ON t.request_id=r.id WHERE t.public_id=? FOR SHARE OF r",
        Long.class,
        taskPublicId);
  }

  public void shareLockCollectionTask(String taskPublicId) {
    jdbc.queryForList(
        "SELECT id FROM collection_task WHERE public_id=? FOR SHARE", Long.class, taskPublicId);
  }

  public List<Map<String, Object>> resolveLeaseRows(String taskPublicId, String attemptId, String node) {
    return jdbc.queryForList(
        """
        SELECT t.authorization_context,c.ref,c.quota_identity,c.current_version,v.key_id,v.nonce,v.ciphertext,v.expires_at,d.deadline_at
        FROM collection_task t JOIN collection_attempt a ON a.task_id=t.id AND a.public_id=t.current_attempt_id
        JOIN collector_node n ON n.id=a.node_id JOIN collector_dispatch d ON d.attempt_id=a.public_id
        JOIN collection_request r ON r.id=t.request_id
        JOIN github_credential c ON c.ref=t.credential_ref AND NOT c.revoked
        JOIN github_credential_version v ON v.credential_id=c.id AND v.version=c.current_version
        WHERE t.public_id=? AND a.public_id=? AND n.node_key=? AND n.enabled AND r.state='ACTIVE'
          AND a.state IN ('ASSIGNED','ACCEPTED','RUNNING') AND t.state IN ('READY','ACCEPTED','RUNNING','RETRY')
          AND t.authorization_context IS NOT NULL AND t.credential_ref IS NOT NULL
          AND (
            EXISTS (
              SELECT 1 FROM repository_credential b
              WHERE b.repository_id=t.repository_id
                AND b.authorization_context=t.authorization_context
                AND b.credential_id=c.id
            )
            OR EXISTS (
              SELECT 1 FROM credential_discovery_scope s
              WHERE s.credential_id=c.id
                AND s.authorization_context=t.authorization_context
                AND t.task_type='DISCOVER_REPOSITORIES'
            )
          )
        FOR SHARE OF a,t,r,n,c,v
        """,
        taskPublicId,
        attemptId,
        node);
  }
}
