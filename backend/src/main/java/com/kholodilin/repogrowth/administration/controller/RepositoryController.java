package com.kholodilin.repogrowth.administration.controller;

import com.kholodilin.repogrowth.catalog.model.GitHubOwner;
import com.kholodilin.repogrowth.catalog.model.Repository;
import com.kholodilin.repogrowth.catalog.model.RepositoryVisibility;
import com.kholodilin.repogrowth.catalog.repository.RepositoryJdbcRepository.RepositorySignals;
import com.kholodilin.repogrowth.catalog.service.ActivityClassifier;
import com.kholodilin.repogrowth.catalog.service.RepositoryHealthService;
import com.kholodilin.repogrowth.catalog.service.RepositoryService;
import com.kholodilin.repogrowth.common.api.ApiException;
import com.kholodilin.repogrowth.identity.security.CurrentUserAccess;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/repositories")
public class RepositoryController {

  private final RepositoryService repositoryService;
  private final RepositoryHealthService repositoryHealthService;
  private final ActivityClassifier activityClassifier;
  private final CurrentUserAccess currentUserAccess;

  public RepositoryController(
      RepositoryService repositoryService,
      RepositoryHealthService repositoryHealthService,
      ActivityClassifier activityClassifier,
      CurrentUserAccess currentUserAccess) {
    this.repositoryService = repositoryService;
    this.repositoryHealthService = repositoryHealthService;
    this.activityClassifier = activityClassifier;
    this.currentUserAccess = currentUserAccess;
  }

  @GetMapping
  public List<RepositoryResponse> list(@RequestParam(defaultValue = "false") boolean refresh) {
    if (refresh) throw ApiException.validation("Use POST /api/v1/admin/repositories/refresh");
    boolean seePrivate = currentUserAccess.canSeePrivateRepositories();
    // Guests cannot trigger a GitHub refresh; they only read the stored public list.
    List<Repository> repositories = repositoryService.list(seePrivate && refresh);
    Map<Long, RepositorySignals> signals =
        repositoryService.signalsFor(repositories.stream().map(Repository::id).toList());
    return repositories.stream()
        .filter(repository -> seePrivate || RepositoryVisibility.isPublic(repository.visibility()))
        .map(repository -> toResponse(repository, signals.get(repository.id())))
        .toList();
  }

  @GetMapping("/{id}")
  public RepositoryResponse get(@PathVariable long id) {
    Repository repository = repositoryService.get(id);
    requireVisible(repository);
    return toResponse(repository, repositoryService.signalsFor(List.of(id)).get(id));
  }

  private void requireVisible(Repository repository) {
    if (currentUserAccess.canSeePrivateRepositories()
        || RepositoryVisibility.isPublic(repository.visibility())) {
      return;
    }
    throw ApiException.notFound("Repository not found");
  }

  @PostMapping("/{id}/tracking")
  public RepositoryResponse tracking(
      @PathVariable long id, @Valid @RequestBody TrackingRequest request) {
    Repository repository = repositoryService.setTracking(id, request.enabled());
    return toResponse(repository, repositoryService.signalsFor(List.of(id)).get(id));
  }

  private RepositoryResponse toResponse(Repository repository, RepositorySignals signals) {
    GitHubOwner owner = repositoryService.owner(repository.ownerId());
    return new RepositoryResponse(
        repository.id(),
        repository.githubId(),
        repository.name(),
        repository.fullName(),
        repository.description(),
        repository.visibility(),
        repository.defaultBranch(),
        repository.language(),
        repository.fork(),
        repository.archived(),
        repository.stars(),
        repository.watchers(),
        repository.forks(),
        repository.contributors(),
        repository.openIssues(),
        repository.trackingEnabled(),
        signals == null || signals.accessible(),
        signals != null && signals.trafficPermissionMissing(),
        repository.githubCreatedAt(),
        repository.githubUpdatedAt(),
        repository.lastCommitAt(),
        "https://github.com/" + repository.fullName(),
        activityClassifier.classify(repository.archived(), repository.activityAt()).name(),
        repository.activityAt(),
        repositoryService.topics(repository.id()),
        repositoryHealthService.forRepository(repository),
        new RepositoryResponse.OwnerResponse(
            owner.id(),
            owner.githubId(),
            owner.login(),
            owner.ownerType().name(),
            owner.avatarUrl(),
            owner.htmlUrl()));
  }
}
