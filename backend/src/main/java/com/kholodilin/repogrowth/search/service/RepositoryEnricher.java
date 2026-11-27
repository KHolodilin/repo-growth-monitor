package com.kholodilin.repogrowth.search.service;

import com.kholodilin.repogrowth.catalog.model.ActivityStatus;
import com.kholodilin.repogrowth.catalog.model.Repository;
import com.kholodilin.repogrowth.catalog.repository.RepositoryJdbcRepository;
import com.kholodilin.repogrowth.catalog.service.ActivityClassifier;
import com.kholodilin.repogrowth.collection.integration.github.GitHubSearchItem;
import com.kholodilin.repogrowth.search.model.SearchResult;
import java.time.Instant;
import org.springframework.stereotype.Component;

@Component
public class RepositoryEnricher {

  private final RepositoryJdbcRepository repositoryJdbcRepository;
  private final ActivityClassifier activityClassifier;

  public RepositoryEnricher(
      RepositoryJdbcRepository repositoryJdbcRepository, ActivityClassifier activityClassifier) {
    this.repositoryJdbcRepository = repositoryJdbcRepository;
    this.activityClassifier = activityClassifier;
  }

  public SearchResult fromSearchItem(long searchRunId, int position, GitHubSearchItem item) {
    String ownerLogin = item.owner() == null ? "" : item.owner().login();
    Instant activityAt = item.pushedAt();
    SearchResult snapshot =
        new SearchResult(
            null,
            searchRunId,
            position,
            item.id(),
            item.fullName(),
            ownerLogin,
            item.stargazersCount(),
            0,
            item.forksCount(),
            0,
            item.language(),
            item.description(),
            item.htmlUrl() != null ? item.htmlUrl() : "https://github.com/" + item.fullName(),
            item.createdAt(),
            item.updatedAt(),
            activityAt,
            activityClassifier.classify(activityAt),
            null);
    // Prefer already-enriched repository metadata over placeholder zeros (watchers/contributors).
    Repository cached = repositoryJdbcRepository.findByGithubId(item.id()).orElse(null);
    if (cached != null && cached.enrichedAt() != null) {
      return applyCache(snapshot, cached);
    }
    return snapshot;
  }

  private SearchResult applyCache(SearchResult snapshot, Repository cached) {
    Instant activityAt = cached.activityAt() != null ? cached.activityAt() : snapshot.activityAt();
    ActivityStatus status = activityClassifier.classify(cached.archived(), activityAt);
    return new SearchResult(
        snapshot.id(),
        snapshot.searchRunId(),
        snapshot.position(),
        snapshot.githubRepositoryId(),
        cached.fullName(),
        snapshot.owner(),
        cached.stars(),
        cached.watchers(),
        cached.forks(),
        cached.contributors(),
        cached.language() != null ? cached.language() : snapshot.language(),
        cached.description() != null ? cached.description() : snapshot.description(),
        snapshot.htmlUrl(),
        cached.githubCreatedAt() != null
            ? cached.githubCreatedAt()
            : snapshot.repositoryCreatedAt(),
        cached.githubUpdatedAt() != null
            ? cached.githubUpdatedAt()
            : snapshot.repositoryUpdatedAt(),
        activityAt,
        status,
        cached.enrichedAt());
  }
}
