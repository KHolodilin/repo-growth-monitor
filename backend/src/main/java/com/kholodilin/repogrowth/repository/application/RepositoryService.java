package com.kholodilin.repogrowth.repository.application;

import com.kholodilin.repogrowth.collection.planner.CollectionPlanner;
import com.kholodilin.repogrowth.collection.planner.PlanningWindow;
import com.kholodilin.repogrowth.common.api.ApiException;
import com.kholodilin.repogrowth.common.observability.AppMetrics;
import com.kholodilin.repogrowth.github.client.GitHubClient;
import com.kholodilin.repogrowth.github.model.GitHubOwnerResponse;
import com.kholodilin.repogrowth.github.model.GitHubRepositoryResponse;
import com.kholodilin.repogrowth.repository.domain.GitHubOwner;
import com.kholodilin.repogrowth.repository.domain.OwnerType;
import com.kholodilin.repogrowth.repository.domain.Repository;
import com.kholodilin.repogrowth.repository.persistence.GitHubOwnerJdbcRepository;
import com.kholodilin.repogrowth.repository.persistence.RepositoryJdbcRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class RepositoryService {

    private final GitHubClient gitHubClient;
    private final GitHubOwnerJdbcRepository ownerRepository;
    private final RepositoryJdbcRepository repositoryJdbcRepository;
    private final AppMetrics metrics;
    private final CollectionPlanner collectionPlanner;
    private final PlanningWindow planningWindow;

    public RepositoryService(
            GitHubClient gitHubClient,
            GitHubOwnerJdbcRepository ownerRepository,
            RepositoryJdbcRepository repositoryJdbcRepository,
            AppMetrics metrics,
            CollectionPlanner collectionPlanner,
            PlanningWindow planningWindow
    ) {
        this.gitHubClient = gitHubClient;
        this.ownerRepository = ownerRepository;
        this.repositoryJdbcRepository = repositoryJdbcRepository;
        this.metrics = metrics;
        this.collectionPlanner = collectionPlanner;
        this.planningWindow = planningWindow;
    }

    @Transactional
    public List<Repository> list(boolean refresh) {
        if (refresh) {
            syncFromGitHub();
        }
        List<Repository> repositories = repositoryJdbcRepository.findAccountAccessible();
        metrics.setTrackedRepositories(repositoryJdbcRepository.countTracked());
        return repositories;
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
        for (GitHubRepositoryResponse remote : gitHubClient.listAccessibleRepositories()) {
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
        }
    }

    public List<String> topics(long repositoryId) {
        return repositoryJdbcRepository.findTopics(repositoryId);
    }
}
