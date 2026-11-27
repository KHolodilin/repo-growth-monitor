package com.kholodilin.repogrowth.collection.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persistence for shared GitHub quota budget rows and request permits. */
@Repository
@ConditionalOnProperty(name = "rgm.credentials.enabled", havingValue = "true")
public class GitHubBudgetJdbcRepository {

  private final JdbcTemplate jdbc;

  public GitHubBudgetJdbcRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static Timestamp ts(Instant time) {
    return Timestamp.from(time);
  }

  public void advisoryLock(String identity) {
    jdbc.queryForObject(
        "SELECT pg_advisory_xact_lock(hashtextextended(?,0))", Object.class, identity);
  }

  public Instant clockTimestamp() {
    return jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
  }

  public List<Map<String, Object>> findPermit(UUID permitId) {
    return jdbc.queryForList("SELECT * FROM github_request_permit WHERE permit_id=?", permitId);
  }

  public void ensureBudgetRow(String identity, String resource, long ceiling, Instant resetAt, Instant nextAt) {
    jdbc.update(
        "INSERT INTO github_budget(identity_key,resource,remaining,reset_at,next_request_at) VALUES (?,?,?, ?,?) ON CONFLICT DO NOTHING",
        identity,
        resource,
        ceiling,
        ts(resetAt),
        ts(nextAt));
  }

  public Map<String, Object> lockBudget(String identity, String resource) {
    return jdbc.queryForMap(
        "SELECT * FROM github_budget WHERE identity_key=? AND resource=? FOR UPDATE",
        identity,
        resource);
  }

  public List<Timestamp> activeLeaseUntil(String identity) {
    return jdbc.queryForList(
        "SELECT max(leased_until) FROM github_request_permit WHERE (identity_key=? OR ?='github:unclassified') HAVING count(*)>0",
        Timestamp.class,
        identity,
        identity);
  }

  public void deleteExpiredPermits(String identity, Instant now) {
    jdbc.update(
        "DELETE FROM github_request_permit WHERE identity_key=? AND leased_until<=?",
        identity,
        ts(now));
  }

  public void updateBudgetAfterGrant(
      long remaining,
      Instant resetAt,
      Instant nextRequestAt,
      Instant updatedAt,
      String identity,
      String resource) {
    jdbc.update(
        "UPDATE github_budget SET remaining=?,reset_at=?,next_request_at=?,updated_at=? WHERE identity_key=? AND resource=?",
        remaining,
        ts(resetAt),
        ts(nextRequestAt),
        ts(updatedAt),
        identity,
        resource);
  }

  public void insertPermit(
      UUID permitId,
      String identity,
      String node,
      String attemptId,
      String resource,
      Instant leasedUntil) {
    jdbc.update(
        "INSERT INTO github_request_permit(permit_id,identity_key,node_key,attempt_id,resource,generation,leased_until) VALUES (?,?,?,?,?,1,?)",
        permitId,
        identity,
        node,
        attemptId,
        resource,
        ts(leasedUntil));
  }

  public List<String> permitIdentities(UUID permitId, String node) {
    return jdbc.queryForList(
        "SELECT identity_key FROM github_request_permit WHERE permit_id=? AND node_key=?",
        String.class,
        permitId,
        node);
  }

  public List<Map<String, Object>> lockPermit(UUID permitId, String node) {
    return jdbc.queryForList(
        "SELECT * FROM github_request_permit WHERE permit_id=? AND node_key=? FOR UPDATE",
        permitId,
        node);
  }

  public void lowerRemaining(long remaining, String identity, String resource) {
    jdbc.update(
        "UPDATE github_budget SET remaining=least(remaining,?) WHERE identity_key=? AND resource=?",
        remaining,
        identity,
        resource);
  }

  public void extendResetAt(Instant resetAt, String identity, String resource) {
    jdbc.update(
        "UPDATE github_budget SET reset_at=greatest(reset_at,?) WHERE identity_key=? AND resource=?",
        ts(resetAt),
        identity,
        resource);
  }

  public void extendCooldown(Instant cooldown, String identity) {
    jdbc.update(
        "UPDATE github_budget SET cooldown_until=greatest(cooldown_until,?) WHERE identity_key=?",
        ts(cooldown),
        identity);
  }

  public void insertReceipt(UUID permitId) {
    jdbc.update(
        "INSERT INTO github_budget_receipt(permit_id) VALUES (?) ON CONFLICT DO NOTHING", permitId);
  }

  public void deletePermit(UUID permitId) {
    jdbc.update("DELETE FROM github_request_permit WHERE permit_id=?", permitId);
  }

  public List<Map<String, Object>> listBudgets() {
    return jdbc.queryForList(
        "SELECT identity_key,resource,remaining,reset_at,next_request_at,cooldown_until FROM github_budget ORDER BY identity_key,resource");
  }
}
