package com.kholodilin.repogrowth.auth.security;

import com.kholodilin.repogrowth.common.api.ApiException;
import com.kholodilin.repogrowth.common.api.ApiSurface;
import com.kholodilin.repogrowth.publication.PublicationService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Shared resource/action checks for the unified resource API. Filter visibility before totals,
 * counts, sort and pagination. Ops surfaces stay separate from resource reads — do not simply
 * permitAll admin GETs when migrating URLs.
 */
@Component
public class ResourceAccessPolicy {

  public enum Decision {
    ALLOW,
    UNAUTHENTICATED,
    FORBIDDEN,
    NOT_FOUND
  }

  public enum ResourceAction {
    READ,
    MUTATE
  }

  private final CurrentUserAccess currentUserAccess;
  private final PublicationService publicationService;

  public ResourceAccessPolicy(
      CurrentUserAccess currentUserAccess, PublicationService publicationService) {
    this.currentUserAccess = currentUserAccess;
    this.publicationService = publicationService;
  }

  /** Admins see every repository; anonymous/user only published modules. */
  public Decision canReadRepository(long repositoryId, PublicationService.Module module) {
    if (currentUserAccess.canSeePrivateRepositories()) {
      return Decision.ALLOW;
    }
    if (!publicationService.allows(repositoryId, module)) {
      return Decision.NOT_FOUND;
    }
    return Decision.ALLOW;
  }

  public Decision can(long repositoryId, ResourceAction action, PublicationService.Module module) {
    if (action == ResourceAction.MUTATE) {
      if (!currentUserAccess.canSeePrivateRepositories()) {
        return currentUserAccess.authenticated() ? Decision.FORBIDDEN : Decision.UNAUTHENTICATED;
      }
      return Decision.ALLOW;
    }
    return canReadRepository(repositoryId, module);
  }

  /**
   * Resource reads hide inaccessible repositories as NOT_FOUND — never leak existence via 403 when
   * the caller must not know the resource exists.
   */
  public void requireReadable(long repositoryId, PublicationService.Module module) {
    Decision decision = canReadRepository(repositoryId, module);
    if (decision == Decision.ALLOW) {
      return;
    }
    if (decision == Decision.UNAUTHENTICATED) {
      throw ApiException.unauthorized("Authentication required");
    }
    if (decision == Decision.FORBIDDEN) {
      throw ApiException.forbidden("Forbidden");
    }
    throw ApiException.notFound("Repository not found");
  }

  public void requireAction(
      long repositoryId, ResourceAction action, PublicationService.Module module) {
    Decision decision = can(repositoryId, action, module);
    if (decision == Decision.ALLOW) {
      return;
    }
    if (decision == Decision.UNAUTHENTICATED) {
      throw ApiException.unauthorized("Authentication required");
    }
    if (decision == Decision.FORBIDDEN) {
      throw ApiException.forbidden("Forbidden");
    }
    throw ApiException.notFound("Repository not found");
  }

  /**
   * Keep only repositories the caller may read. Apply this before count, cursor slicing and sort so
   * hidden rows never appear in totals or nextCursor.
   */
  public List<Long> filterReadable(
      Collection<Long> repositoryIds, PublicationService.Module module) {
    if (repositoryIds == null || repositoryIds.isEmpty()) {
      return List.of();
    }
    if (currentUserAccess.canSeePrivateRepositories()) {
      return List.copyOf(repositoryIds);
    }
    List<Long> visible = new ArrayList<>();
    for (Long id : repositoryIds) {
      if (id != null && publicationService.allows(id, module)) {
        visible.add(id);
      }
    }
    return List.copyOf(visible);
  }

  public boolean isOpsSurface(String path) {
    return surfaceOf(path) == ApiSurface.OPS || surfaceOf(path) == ApiSurface.INTERNAL;
  }

  public ApiSurface surfaceOf(String path) {
    if (path == null) {
      return ApiSurface.RESOURCE;
    }
    if (path.startsWith("/internal/v1/")) {
      return ApiSurface.INTERNAL;
    }
    if (path.startsWith("/api/v1/admin/collectors")
        || path.startsWith("/api/v1/admin/github-budget")
        || path.startsWith("/api/v1/admin/system")
        || path.startsWith("/api/v1/admin/collection/plan")
        || path.startsWith("/api/v1/admin/credentials")
        || path.startsWith("/api/v1/admin/dashboard")
        || path.equals("/api/v1/admin/collectors")
        || path.startsWith("/admin")) {
      return ApiSurface.OPS;
    }
    return ApiSurface.RESOURCE;
  }
}
