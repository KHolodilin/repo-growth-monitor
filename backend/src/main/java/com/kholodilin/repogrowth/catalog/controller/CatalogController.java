package com.kholodilin.repogrowth.catalog.controller;

import com.kholodilin.repogrowth.administration.service.PublicationService;
import com.kholodilin.repogrowth.catalog.model.GitHubOwner;
import com.kholodilin.repogrowth.catalog.model.Repository;
import com.kholodilin.repogrowth.catalog.repository.OwnerDirectoryReadModel;
import com.kholodilin.repogrowth.catalog.service.ActivityClassifier;
import com.kholodilin.repogrowth.catalog.service.RepositoryHealthService;
import com.kholodilin.repogrowth.catalog.service.RepositoryService;
import com.kholodilin.repogrowth.common.api.ApiException;
import com.kholodilin.repogrowth.common.api.ApiSurface;
import com.kholodilin.repogrowth.common.model.page.CursorPage;
import com.kholodilin.repogrowth.identity.security.ResourceAccessPolicy;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unified catalog resource reads ({@link ApiSurface#RESOURCE}). Exact {@code login}/{@code name}
 * filters support F5 without localStorage; internal IDs are the API identity.
 */
@RestController
@RequestMapping("/api/v1")
public class CatalogController {

  public record OwnerAbout(
      String ownerId, String login, String ownerType, String avatarUrl, String htmlUrl) {}

  public record RepositoryCard(
      String repositoryId,
      String ownerId,
      String name,
      String fullName,
      String description,
      String language,
      boolean archived,
      boolean fork,
      String activityStatus,
      Modules modules) {}

  public record Modules(
      boolean dashboard, boolean traffic, boolean referrers, boolean paths, boolean topics) {}

  public record RepositoryHealth(String repositoryId, Object health) {}

  private final ResourceAccessPolicy access;
  private final OwnerDirectoryReadModel owners;
  private final RepositoryService repositories;
  private final RepositoryHealthService health;
  private final ActivityClassifier activity;
  private final PublicationService publication;

  public CatalogController(
      ResourceAccessPolicy access,
      OwnerDirectoryReadModel owners,
      RepositoryService repositories,
      RepositoryHealthService health,
      ActivityClassifier activity,
      PublicationService publication) {
    this.access = access;
    this.owners = owners;
    this.repositories = repositories;
    this.health = health;
    this.activity = activity;
    this.publication = publication;
  }

  @GetMapping("/owners/{ownerId}")
  public OwnerAbout owner(@PathVariable String ownerId) {
    long id = parseId(ownerId, "ownerId");
    requireOwnerVisible(id);
    GitHubOwner owner = repositories.owner(id);
    return new OwnerAbout(
        Long.toString(owner.id()),
        owner.login(),
        owner.ownerType() == null ? null : owner.ownerType().name(),
        owner.avatarUrl(),
        owner.htmlUrl());
  }

  @GetMapping("/owners/{ownerId}/repositories")
  public CursorPage<RepositoryCard> ownerRepositories(
      @PathVariable String ownerId,
      @RequestParam(required = false) String name,
      @RequestParam(required = false) String cursor,
      @RequestParam(defaultValue = "20") int limit) {
    long id = parseId(ownerId, "ownerId");
    requireOwnerVisible(id);
    int pageSize = Math.min(Math.max(limit, 1), 100);
    String exactName = name == null || name.isBlank() ? null : name.trim();
    List<Repository> listed =
        repositories.list(false).stream().filter(repo -> repo.ownerId() == id).toList();
    List<Long> readable =
        access.filterReadable(
            listed.stream().map(Repository::id).toList(), PublicationService.Module.DASHBOARD);
    List<RepositoryCard> cards = new ArrayList<>();
    for (Repository repository : listed) {
      if (!readable.contains(repository.id())) {
        continue;
      }
      if (exactName != null && !repository.name().equalsIgnoreCase(exactName)) {
        continue;
      }
      cards.add(toCard(repository));
    }
    return page(cards, cursor, pageSize);
  }

  @GetMapping("/repositories/{repositoryId}")
  public RepositoryCard repository(@PathVariable String repositoryId) {
    long id = parseId(repositoryId, "repositoryId");
    access.requireReadable(id, PublicationService.Module.DASHBOARD);
    return toCard(repositories.get(id));
  }

  @GetMapping("/repositories/{repositoryId}/health")
  public RepositoryHealth repositoryHealth(@PathVariable String repositoryId) {
    long id = parseId(repositoryId, "repositoryId");
    access.requireReadable(id, PublicationService.Module.DASHBOARD);
    return new RepositoryHealth(Long.toString(id), health.forRepository(repositories.get(id)));
  }

  private void requireOwnerVisible(long ownerId) {
    List<OwnerDirectoryReadModel.OwnerRow> rows = owners.list(null);
    OwnerDirectoryReadModel.OwnerRow match =
        rows.stream().filter(row -> row.ownerId() == ownerId).findFirst().orElse(null);
    if (match == null) {
      throw ApiException.notFound("Owner not found");
    }
    List<Long> readable =
        access.filterReadable(match.repositoryIds(), PublicationService.Module.DASHBOARD);
    if (readable.isEmpty()) {
      throw ApiException.notFound("Owner not found");
    }
  }

  private RepositoryCard toCard(Repository repository) {
    String activityStatus =
        activity.classify(repository.archived(), repository.activityAt()).name();
    Modules modules =
        new Modules(
            publication.allows(repository.id(), PublicationService.Module.DASHBOARD),
            publication.allows(repository.id(), PublicationService.Module.TRAFFIC),
            publication.allows(repository.id(), PublicationService.Module.REFERRERS),
            publication.allows(repository.id(), PublicationService.Module.PATHS),
            publication.allows(repository.id(), PublicationService.Module.TOPICS));
    return new RepositoryCard(
        Long.toString(repository.id()),
        Long.toString(repository.ownerId()),
        repository.name(),
        repository.fullName(),
        repository.description(),
        repository.language(),
        repository.archived(),
        repository.fork(),
        activityStatus,
        modules);
  }

  private static CursorPage<RepositoryCard> page(
      List<RepositoryCard> cards, String cursor, int pageSize) {
    long after = parseCursor(cursor);
    List<RepositoryCard> pageItems = new ArrayList<>();
    String next = null;
    for (RepositoryCard card : cards) {
      long id = Long.parseLong(card.repositoryId());
      if (id <= after) {
        continue;
      }
      if (pageItems.size() == pageSize) {
        next = card.repositoryId();
        break;
      }
      pageItems.add(card);
    }
    return CursorPage.of(pageItems, next);
  }

  private static long parseId(String raw, String field) {
    try {
      long value = Long.parseLong(raw.trim());
      if (value <= 0) {
        throw ApiException.validation("Invalid " + field);
      }
      return value;
    } catch (NumberFormatException ex) {
      throw ApiException.validation("Invalid " + field);
    }
  }

  private static long parseCursor(String cursor) {
    if (cursor == null || cursor.isBlank()) {
      return 0L;
    }
    try {
      long value = Long.parseLong(cursor.trim());
      if (value < 0) {
        throw ApiException.validation("Invalid cursor");
      }
      return value;
    } catch (NumberFormatException ex) {
      throw ApiException.validation("Invalid cursor");
    }
  }
}
