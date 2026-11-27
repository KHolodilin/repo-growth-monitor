package com.kholodilin.repogrowth.administration.repository;

import com.kholodilin.repogrowth.administration.model.PublicationModule;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persistence for publication flags, visibility verification, and public path resolution. */
@Repository
public class PublicationJdbcRepository {

  private final JdbcTemplate jdbc;

  public PublicationJdbcRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  private static String eligibleSql() {
    return "r.public_dashboard_enabled AND r.visibility='PUBLIC' AND r.visibility_verified_at<=clock_timestamp() AND r.visibility_verified_at>clock_timestamp()-(?*interval '1 millisecond')";
  }

  public Optional<Map<String, Object>> lockRepositoryPublicationRow(long repositoryId) {
    var rows =
        jdbc.queryForList(
            "SELECT o.login,r.visibility,r.visibility_verified_at FROM repository r JOIN github_owner o ON o.id=r.owner_id WHERE r.id=? FOR UPDATE OF r",
            repositoryId);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }

  public void updatePublicationFlags(
      long repositoryId,
      boolean dashboard,
      boolean traffic,
      boolean referrers,
      boolean paths,
      boolean topics) {
    jdbc.update(
        "UPDATE repository SET public_dashboard_enabled=?,public_traffic_enabled=?,public_referrers_enabled=?,public_paths_enabled=?,public_topics_enabled=? WHERE id=?",
        dashboard,
        traffic,
        referrers,
        paths,
        topics,
        repositoryId);
  }

  public List<String> eligibleOwnerLogins(long repositoryId, Duration verificationMaxAge) {
    return jdbc.queryForList(
        "SELECT o.login FROM repository r JOIN github_owner o ON o.id=r.owner_id WHERE r.id=? AND "
            + eligibleSql(),
        String.class,
        repositoryId,
        verificationMaxAge.toMillis());
  }

  public List<String> allowedOwnerLogins(
      long repositoryId, PublicationModule module, Duration verificationMaxAge) {
    String flag =
        switch (module) {
          case DASHBOARD -> "public_dashboard_enabled";
          case TRAFFIC -> "public_traffic_enabled";
          case REFERRERS -> "public_referrers_enabled";
          case PATHS -> "public_paths_enabled";
          case TOPICS -> "public_topics_enabled";
        };
    return jdbc.queryForList(
        "SELECT o.login FROM repository r JOIN github_owner o ON o.id=r.owner_id WHERE r.id=? AND r."
            + flag
            + " AND "
            + eligibleSql(),
        String.class,
        repositoryId,
        verificationMaxAge.toMillis());
  }

  public List<String> queryEligibleOwnerLogins(long queryId, Duration verificationMaxAge) {
    return jdbc.queryForList(
        "SELECT o.login FROM search_query q JOIN repository r ON r.id=q.repository_id JOIN github_owner o ON o.id=r.owner_id WHERE q.id=? AND q.public_enabled AND "
            + eligibleSql(),
        String.class,
        queryId,
        verificationMaxAge.toMillis());
  }

  public void updateRepositoryOwnerAndName(long ownerId, String name, long repositoryId, long githubId) {
    jdbc.update(
        "UPDATE repository SET owner_id=?,name=? WHERE id=? AND github_id=?",
        ownerId,
        name,
        repositoryId,
        githubId);
  }

  public boolean updateVisibilityIfFresher(
      long repositoryId, long githubId, String visibility, Instant observedAt) {
    return jdbc.update(
            "UPDATE repository SET visibility=?,visibility_verified_at=? WHERE id=? AND github_id=? AND (visibility_verified_at IS NULL OR visibility_verified_at<=?)",
            visibility,
            Timestamp.from(observedAt),
            repositoryId,
            githubId,
            Timestamp.from(observedAt))
        == 1;
  }

  public List<Map<String, Object>> findOwnersByLogin(String login) {
    return jdbc.queryForList(
        "SELECT id,login FROM github_owner WHERE lower(login)=lower(?)", login);
  }

  public List<Map<String, Object>> findOwnersByAlias(String login) {
    return jdbc.queryForList(
        "SELECT o.id,o.login FROM github_owner_alias a JOIN github_owner o ON o.id=a.owner_id WHERE lower(a.login)=lower(?)",
        login);
  }

  public long countEligibleRepositories(long ownerId, Duration verificationMaxAge) {
    Long count =
        jdbc.queryForObject(
            "SELECT count(*) FROM repository r WHERE r.owner_id=? AND " + eligibleSql(),
            Long.class,
            ownerId,
            verificationMaxAge.toMillis());
    return count == null ? 0L : count;
  }

  public List<Long> findRepositoryIdsByOwnerAndName(long ownerId, String name) {
    return jdbc.queryForList(
        "SELECT id FROM repository WHERE owner_id=? AND lower(name)=lower(?)",
        Long.class,
        ownerId,
        name);
  }

  public List<Long> findRepositoryIdsByPathAlias(String ownerLogin, String repositoryName) {
    return jdbc.queryForList(
        "SELECT repository_id FROM repository_path_alias WHERE lower(owner_login)=lower(?) AND lower(repository_name)=lower(?)",
        Long.class,
        ownerLogin,
        repositoryName);
  }

  public List<Long> findRepositoryIdsByOwnerAndAliasName(long ownerId, String repositoryName) {
    return jdbc.queryForList(
        "SELECT DISTINCT r.id FROM repository_path_alias a JOIN repository r ON r.id=a.repository_id WHERE r.owner_id=? AND lower(a.repository_name)=lower(?)",
        Long.class,
        ownerId,
        repositoryName);
  }

  public Map<String, Object> repositoryCanonicalPathParts(long repositoryId) {
    return jdbc.queryForMap(
        "SELECT r.owner_id,o.login,r.name FROM repository r JOIN github_owner o ON o.id=r.owner_id WHERE r.id=?",
        repositoryId);
  }

  public List<Map<String, Object>> publicationAdminRow(long repositoryId) {
    return jdbc.queryForList(
        "SELECT (SELECT '/'||o.login||'/'||r.name FROM repository r JOIN github_owner o ON o.id=r.owner_id WHERE r.id=repository.id) AS \"canonicalPath\",public_dashboard_enabled,public_traffic_enabled,public_referrers_enabled,public_paths_enabled,public_topics_enabled,publication_revision,visibility_verified_at FROM repository WHERE id=?",
        repositoryId);
  }

  public void lockRepositoryId(long repositoryId) {
    jdbc.queryForList("SELECT id FROM repository WHERE id=? FOR UPDATE", repositoryId);
  }

  public List<Map<String, Object>> lockSearchQueryPublication(long queryId) {
    return jdbc.queryForList(
        "SELECT public_enabled,repository_id FROM search_query WHERE id=? FOR UPDATE", queryId);
  }

  public void updateSearchQueryPublicEnabled(long queryId, boolean enabled) {
    jdbc.update("UPDATE search_query SET public_enabled=? WHERE id=?", enabled, queryId);
  }

  public void insertPublicationAudit(
      long actorId,
      String entityType,
      long entityId,
      String beforePolicyJson,
      String afterPolicyJson,
      String requestId) {
    jdbc.update(
        "INSERT INTO publication_audit(actor_id,entity_type,entity_id,before_policy,after_policy,request_id) VALUES (?,?,?,?::jsonb,?::jsonb,?)",
        actorId,
        entityType,
        entityId,
        beforePolicyJson,
        afterPolicyJson,
        requestId);
  }
}
