package com.kholodilin.repogrowth.auth.security;

import com.kholodilin.repogrowth.publication.PublicationService;
import org.springframework.stereotype.Component;

/**
 * Shared resource/action checks for the future unified API. Phase 0 skeleton: wrap publication +
 * admin visibility without expanding anonymous access. Controllers and filters migrate onto this
 * policy in later phases; do not "simply permitAll" admin GETs.
 */
@Component
public class ResourceAccessPolicy {

  public enum Decision {
    ALLOW,
    UNAUTHENTICATED,
    FORBIDDEN,
    NOT_FOUND
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

  public boolean isOpsSurface(String path) {
    if (path == null) {
      return false;
    }
    return path.startsWith("/api/v1/admin/collectors")
        || path.startsWith("/api/v1/admin/github-budget")
        || path.startsWith("/api/v1/admin/system")
        || path.startsWith("/api/v1/admin/collection/plan")
        || path.startsWith("/api/v1/admin/credentials")
        || path.startsWith("/internal/v1/");
  }
}
