package com.kholodilin.repogrowth.repository.application;

import com.kholodilin.repogrowth.collection.planner.CollectionPlanner;
import com.kholodilin.repogrowth.collection.planner.PlanningWindow;
import com.kholodilin.repogrowth.common.api.ApiException;
import com.kholodilin.repogrowth.common.config.GitHubProperties;
import com.kholodilin.repogrowth.common.observability.AppMetrics;
import com.kholodilin.repogrowth.github.exception.GitHubException;
import com.kholodilin.repogrowth.github.client.GitHubClient;
import com.kholodilin.repogrowth.github.model.GitHubOwnerResponse;
import com.kholodilin.repogrowth.github.model.GitHubRepositoryResponse;
import com.kholodilin.repogrowth.repository.domain.GitHubOwner;
import com.kholodilin.repogrowth.repository.domain.OwnerType;
import com.kholodilin.repogrowth.repository.domain.Repository;
import com.kholodilin.repogrowth.repository.persistence.GitHubOwnerJdbcRepository;
import com.kholodilin.repogrowth.repository.persistence.RepositoryJdbcRepository;
import com.kholodilin.repogrowth.repository.persistence.RepositoryJdbcRepository.RepositorySignals;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class RepositoryService {

    private final GitHubClient gitHubClient;
    private final GitHubProperties gitHubProperties;
    private final GitHubOwnerJdbcRepository ownerRepository;
    private final RepositoryJdbcRepository repositoryJdbcRepository;
    private final AppMetrics metrics;
    private final CollectionPlanner collectionPlanner;
    private final PlanningWindow planningWindow;

    public RepositoryService(
            GitHubClient gitHubClient,
            GitHubProperties gitHubProperties,
            GitHubOwnerJdbcRepository ownerRepository,
            RepositoryJdbcRepository repositoryJdbcRepository,
            AppMetrics metrics,
            CollectionPlanner collectionPlanner,
            PlanningWindow planningWindow
    ) {
        this.gitHubClient = gitHubClient;
        this.gitHubProperties = gitHubProperties;
        this.ownerRepository = ownerRepository;
        this.repositoryJdbcRepository = repositoryJdbcRepository;
        this.metrics = metrics;
        this.collectionPlanner = collectionPlanner;
        this.planningWindow = planningWindow;
    }

    @Transactional
    public List<Repository> list(boolean refresh) {
        if (!gitHubProperties.tokenConfigured()) {
            return List.of();
        }
        if (refresh) {
            if (gitHubClient.tokenRejected()) {
                throw GitHubException.auth(401, "GitHub rejected the token");
            }
            syncFromGitHub();
        }
        List<Repository> repositories = repositoryJdbcRepository.findListed();
        metrics.setTrackedRepositories(repositoryJdbcRepository.countTracked());
        return repositories;
    }

    public Map<Long, RepositorySignals> signalsFor(Collection<Long> ids) {
        return repositoryJdbcRepository.signalsFor(ids);
    }

    public Repository get(long id) {
        return repositoryJdbcRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Repository not found"));
    }

    public GitHubOwner owner(long ownerId) {
        GitHubOwner owner = ownerRepository.getById(ownerId);
        if (owner == null) {
            throw ApiException.notFound("Owner not found");
        }
        return owner;
    }

    @Transactional
    public Repository setTracking(long id, boolean enabled) {
        get(id);
        if (enabled && !repositoryJdbcRepository.isAccountAccessible(id)) {
            throw ApiException.validation("Only repositories available to the GitHub token can be tracked");
        }
        Repository updated = repositoryJdbcRepository.setTracking(id, enabled);
        metrics.setTrackedRepositories(repositoryJdbcRepository.countTracked());
        if (enabled) {
            collectionPlanner.planRepository(id, planningWindow.businessDate(), true);
        }
        return updated;
    }

    public List<Repository> tracked() {
        return repositoryJdbcRepository.findTracked();
    }

    private void syncFromGitHub() {
        List<GitHubRepositoryResponse> remoteRepositories = gitHubClient.listAccessibleRepositories();
        Set<Long> seen = new HashSet<>();
        for (GitHubRepositoryResponse remote : remoteRepositories) {
            GitHubOwnerResponse ownerResponse = remote.owner();
            OwnerType ownerType = "Organization".equalsIgnoreCase(ownerResponse.type())
                    ? OwnerType.ORGANIZATION
                    : OwnerType.USER;
            GitHubOwner owner = ownerRepository.upsert(
                    ownerResponse.id(),
                    ownerResponse.login(),
                    ownerType,
                    ownerResponse.avatarUrl(),
                    ownerResponse.htmlUrl()
            );
            Repository stored = repositoryJdbcRepository.upsertKeepingTracking(new Repository(
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
                    0,
                    false,
                    remote.createdAt(),
                    remote.updatedAt(),
                    remote.pushedAt(),
                    null,
                    null,
                    null,
                    null,
                    null
            ));
            repositoryJdbcRepository.markAccountAccessible(stored.id());
            repositoryJdbcRepository.replaceTopics(stored.id(), remote.topicsOrEmpty());
            seen.add(stored.id());
        }
        repositoryJdbcRepository.markInaccessibleExcept(seen);
    }

    public List<String> topics(long repositoryId) {
        return repositoryJdbcRepository.findTopics(repositoryId);
    }
}
