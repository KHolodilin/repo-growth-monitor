package com.kholodilin.repogrowth.administration.service;

import com.kholodilin.repogrowth.administration.configuration.PublicRoutes;
import com.kholodilin.repogrowth.administration.configuration.PublicationProperties;
import com.kholodilin.repogrowth.administration.model.PublicPathResolution;
import com.kholodilin.repogrowth.administration.model.PublicationModule;
import com.kholodilin.repogrowth.administration.model.PublicationPolicy;
import com.kholodilin.repogrowth.administration.repository.PublicationJdbcRepository;
import com.kholodilin.repogrowth.catalog.model.OwnerType;
import com.kholodilin.repogrowth.catalog.repository.GitHubOwnerJdbcRepository;
import com.kholodilin.repogrowth.collection.integration.github.GitHubRepositoryResponse;
import com.kholodilin.repogrowth.common.api.ApiException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@EnableConfigurationProperties(PublicationProperties.class)
public class PublicationService {

  /** @deprecated Prefer {@link PublicationPolicy}. Kept as a stable nested alias for callers. */
  public record Policy(
      boolean dashboard, boolean traffic, boolean referrers, boolean paths, boolean topics) {
    public PublicationPolicy toModel() {
      return new PublicationPolicy(dashboard, traffic, referrers, paths, topics);
    }

    public static Policy from(PublicationPolicy policy) {
      return new Policy(
          policy.dashboard(),
          policy.traffic(),
          policy.referrers(),
          policy.paths(),
          policy.topics());
    }
  }

  /** @deprecated Prefer {@link PublicPathResolution}. */
  public record Resolved(long ownerId, Long repositoryId, String canonicalPath, boolean redirect) {
    public static Resolved from(PublicPathResolution resolution) {
      return new Resolved(
          resolution.ownerId(),
          resolution.repositoryId(),
          resolution.canonicalPath(),
          resolution.redirect());
    }
  }

  /** @deprecated Prefer {@link PublicationModule}. */
  public enum Module {
    DASHBOARD,
    TRAFFIC,
    REFERRERS,
    PATHS,
    TOPICS;

    public PublicationModule toModel() {
      return PublicationModule.valueOf(name());
    }
  }

  private final PublicationJdbcRepository publication;
  private final PublicationProperties properties;

  public PublicationService(
      PublicationJdbcRepository publication, PublicationProperties properties) {
    this.publication = publication;
    this.properties = properties;
  }

  @Transactional
  public void publish(long repository, Policy policy) {
    publish(repository, policy.toModel());
  }

  @Transactional
  public void publish(long repository, PublicationPolicy policy) {
    var row =
        publication
            .lockRepositoryPublicationRow(repository)
            .orElseThrow(() -> ApiException.notFound("Repository not found"));
    if (policy.dashboard() && !PublicRoutes.owner((String) row.get("login")))
      throw ApiException.validation("Owner conflicts with reserved public namespace");
    if (policy.dashboard()) {
      var checked = (java.sql.Timestamp) row.get("visibility_verified_at");
      if (!"PUBLIC".equals(row.get("visibility"))
          || checked == null
          || checked.toInstant().plus(properties.verificationMaxAge()).isBefore(Instant.now())
          || checked.toInstant().isAfter(Instant.now()))
        throw ApiException.validation("Verified public visibility is required before publication");
    }
    publication.updatePublicationFlags(
        repository,
        policy.dashboard(),
        policy.traffic(),
        policy.referrers(),
        policy.paths(),
        policy.topics());
  }

  public boolean eligible(long id) {
    var names = publication.eligibleOwnerLogins(id, properties.verificationMaxAge());
    return !names.isEmpty() && PublicRoutes.owner(names.getFirst());
  }

  public boolean allows(long id, Module module) {
    return allows(id, module.toModel());
  }

  public boolean allows(long id, PublicationModule module) {
    var names = publication.allowedOwnerLogins(id, module, properties.verificationMaxAge());
    return !names.isEmpty() && PublicRoutes.owner(names.getFirst());
  }

  public boolean queryEligible(long query) {
    var names = publication.queryEligibleOwnerLogins(query, properties.verificationMaxAge());
    return !names.isEmpty() && PublicRoutes.owner(names.getFirst());
  }

  @Autowired private GitHubOwnerJdbcRepository owners;

  @Transactional
  public void metadata(long id, GitHubRepositoryResponse remote, Instant at) {
    if (!verify(id, remote.id(), remote.resolvedVisibility(), at)) return;
    if (remote.owner() != null) {
      var owner = remote.owner();
      var stored =
          owners.upsert(
              owner.id(),
              owner.login(),
              "Organization".equalsIgnoreCase(owner.type())
                  ? OwnerType.ORGANIZATION
                  : OwnerType.USER,
              null,
              null);
      publication.updateRepositoryOwnerAndName(stored.id(), remote.name(), id, remote.id());
    }
  }

  @Transactional
  public boolean verify(long id, long githubId, String visibility, Instant observedAt) {
    if (!Set.of("PUBLIC", "PRIVATE", "INTERNAL", "DELETED").contains(visibility)
        || observedAt == null
        || observedAt.isAfter(Instant.now().plusSeconds(5)))
      throw ApiException.validation("Invalid metadata verification");
    return publication.updateVisibilityIfFresher(id, githubId, visibility, observedAt);
  }

  public Resolved resolve(String path) {
    return Resolved.from(resolvePath(path));
  }

  public PublicPathResolution resolvePath(String path) {
    if (path == null || !path.startsWith("/")) throw missing();
    String clean = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    String[] parts = clean.substring(1).split("/", -1);
    if (parts.length < 1
        || parts.length > 2
        || !PublicRoutes.owner(parts[0])
        || (parts.length == 2 && !PublicRoutes.repository(parts[1]))) throw missing();
    var owners = publication.findOwnersByLogin(parts[0]);
    boolean currentOwner = !owners.isEmpty();
    if (!currentOwner) owners = publication.findOwnersByAlias(parts[0]);
    if (parts.length == 1) {
      if (owners.isEmpty()) throw missing();
      var owner = owners.getFirst();
      long id = ((Number) owner.get("id")).longValue();
      if (!PublicRoutes.owner((String) owner.get("login"))
          || publication.countEligibleRepositories(id, properties.verificationMaxAge()) == 0)
        throw missing();
      String canonical = "/" + owner.get("login");
      return new PublicPathResolution(id, null, canonical, !path.equals(canonical));
    }
    List<Long> ids =
        currentOwner
            ? publication.findRepositoryIdsByOwnerAndName(
                ((Number) owners.getFirst().get("id")).longValue(), parts[1])
            : List.of();
    // A current canonical entity wins even when unpublished: never fall through to a public alias.
    if (ids.isEmpty()) ids = publication.findRepositoryIdsByPathAlias(parts[0], parts[1]);
    if (ids.isEmpty() && !currentOwner && !owners.isEmpty())
      ids =
          publication.findRepositoryIdsByOwnerAndName(
              ((Number) owners.getFirst().get("id")).longValue(), parts[1]);
    if (ids.isEmpty() && !currentOwner && !owners.isEmpty())
      ids =
          publication.findRepositoryIdsByOwnerAndAliasName(
              ((Number) owners.getFirst().get("id")).longValue(), parts[1]);
    if (ids.size() != 1 || !eligible(ids.getFirst())) throw missing();
    var target = publication.repositoryCanonicalPathParts(ids.getFirst());
    String canonical = "/" + target.get("login") + "/" + target.get("name");
    return new PublicPathResolution(
        ((Number) target.get("owner_id")).longValue(),
        ids.getFirst(),
        canonical,
        !path.equals(canonical));
  }

  private ApiException missing() {
    return ApiException.notFound("Page not found");
  }

  public Map<String, Object> adminPublication(long repositoryId) {
    var rows = publication.publicationAdminRow(repositoryId);
    if (rows.isEmpty()) throw ApiException.notFound("Repository not found");
    return rows.getFirst();
  }

  public void lockRepository(long repositoryId) {
    publication.lockRepositoryId(repositoryId);
  }

  public List<Map<String, Object>> lockSearchQuery(long queryId) {
    return publication.lockSearchQueryPublication(queryId);
  }

  public void setSearchQueryPublicEnabled(long queryId, boolean enabled) {
    publication.updateSearchQueryPublicEnabled(queryId, enabled);
  }

  public void audit(
      long actorId,
      String entityType,
      long entityId,
      String beforePolicyJson,
      String afterPolicyJson,
      String requestId) {
    publication.insertPublicationAudit(
        actorId, entityType, entityId, beforePolicyJson, afterPolicyJson, requestId);
  }
}
