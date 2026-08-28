package com.kholodilin.repogrowth.search.application;

import com.kholodilin.repogrowth.common.config.SearchProperties;
import com.kholodilin.repogrowth.github.client.GitHubClient;
import com.kholodilin.repogrowth.github.exception.GitHubException;
import com.kholodilin.repogrowth.github.model.GitHubOwnerResponse;
import com.kholodilin.repogrowth.github.model.GitHubRepositoryResponse;
import com.kholodilin.repogrowth.github.model.GitHubSearchItem;
import com.kholodilin.repogrowth.repository.domain.GitHubOwner;
import com.kholodilin.repogrowth.repository.domain.OwnerType;
import com.kholodilin.repogrowth.repository.domain.Repository;
import com.kholodilin.repogrowth.repository.persistence.GitHubOwnerJdbcRepository;
import com.kholodilin.repogrowth.repository.persistence.RepositoryJdbcRepository;
import com.kholodilin.repogrowth.search.domain.ActivityStatus;
import com.kholodilin.repogrowth.search.domain.SearchResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Slf4j
@Component
public class RepositoryEnricher {

    private final GitHubClient gitHubClient;
    private final GitHubOwnerJdbcRepository ownerRepository;
    private final RepositoryJdbcRepository repositoryJdbcRepository;
    private final ActivityClassifier activityClassifier;
    private final SearchProperties searchProperties;
    private final Clock clock;

    public RepositoryEnricher(
            GitHubClient gitHubClient,
            GitHubOwnerJdbcRepository ownerRepository,
            RepositoryJdbcRepository repositoryJdbcRepository,
            ActivityClassifier activityClassifier,
            SearchProperties searchProperties,
            Clock clock
    ) {
        this.gitHubClient = gitHubClient;
        this.ownerRepository = ownerRepository;
        this.repositoryJdbcRepository = repositoryJdbcRepository;
        this.activityClassifier = activityClassifier;
        this.searchProperties = searchProperties;
        this.clock = clock;
    }

    public SearchResult fromSearchItem(long searchRunId, int position, GitHubSearchItem item) {
        String ownerLogin = item.owner() == null ? "" : item.owner().login();
        Instant activityAt = item.pushedAt() != null ? item.pushedAt() : item.updatedAt();
        return new SearchResult(
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
                null
        );
    }

    public SearchResult enrich(SearchResult snapshot, GitHubSearchItem item) {
        Repository cached = repositoryJdbcRepository.findByGithubId(snapshot.githubRepositoryId()).orElse(null);
        if (cached != null && fresh(cached.enrichedAt())) {
            return applyCache(snapshot, cached);
        }
        String owner = snapshot.owner();
        String name = nameFromFullName(snapshot.fullName());
        GitHubRepositoryResponse remote = gitHubClient.getRepository(owner, name);
        int contributors = cached == null ? 0 : cached.contributors();
        try {
            contributors = gitHubClient.countContributors(owner, name);
        } catch (GitHubException ex) {
            log.warn("Contributor count failed fullName={} error={}", snapshot.fullName(), ex.errorCode());
        }
        Instant now = Instant.now(clock);
        Repository stored = persist(item, remote, contributors, now);
        return applyCache(snapshot, stored);
    }

    public boolean fresh(Instant enrichedAt) {
        if (enrichedAt == null) {
            return false;
        }
        Duration ttl = searchProperties.enrichmentTtl();
        return Instant.now(clock).isBefore(enrichedAt.plus(ttl));
    }

    private SearchResult applyCache(SearchResult snapshot, Repository cached) {
        Instant activityAt = cached.activityAt() != null ? cached.activityAt() : snapshot.activityAt();
        ActivityStatus status = activityClassifier.classify(activityAt);
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
                cached.githubCreatedAt() != null ? cached.githubCreatedAt() : snapshot.repositoryCreatedAt(),
                cached.githubUpdatedAt() != null ? cached.githubUpdatedAt() : snapshot.repositoryUpdatedAt(),
                activityAt,
                status,
                cached.enrichedAt()
        );
    }

    private Repository persist(GitHubSearchItem item, GitHubRepositoryResponse remote, int contributors, Instant enrichedAt) {
        GitHubOwnerResponse ownerResponse = remote.owner() != null ? remote.owner() : item.owner();
        OwnerType ownerType = ownerResponse != null && "Organization".equalsIgnoreCase(ownerResponse.type())
                ? OwnerType.ORGANIZATION
                : OwnerType.USER;
        GitHubOwner owner = ownerRepository.upsert(
                ownerResponse == null ? item.id() : ownerResponse.id(),
                ownerResponse == null ? item.fullName().split("/")[0] : ownerResponse.login(),
                ownerType,
                ownerResponse == null ? null : ownerResponse.avatarUrl(),
                ownerResponse == null ? null : ownerResponse.htmlUrl()
        );
        return repositoryJdbcRepository.upsertKeepingTracking(new Repository(
                null,
                remote.id(),
                owner.id(),
                remote.name(),
                remote.fullName(),
                remote.description(),
                remote.resolvedVisibility(),
                remote.defaultBranch(),
                remote.language(),
                remote.fork(),
                remote.archived(),
                remote.stargazersCount(),
                remote.watchers(),
                remote.forksCount(),
                remote.openIssuesCount(),
                contributors,
                false,
                remote.createdAt(),
                remote.updatedAt(),
                remote.pushedAt(),
                null,
                null,
                enrichedAt,
                null,
                null
        ));
    }

    private static String nameFromFullName(String fullName) {
        int slash = fullName.lastIndexOf('/');
        return slash < 0 ? fullName : fullName.substring(slash + 1);
    }
}
