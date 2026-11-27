package com.kholodilin.repogrowth.catalog.service;

import com.kholodilin.repogrowth.catalog.model.GitHubOwner;
import com.kholodilin.repogrowth.catalog.model.Repository;
import com.kholodilin.repogrowth.catalog.repository.GitHubOwnerJdbcRepository;
import com.kholodilin.repogrowth.catalog.repository.RepositoryJdbcRepository;
import com.kholodilin.repogrowth.catalog.repository.RepositoryJdbcRepository.RepositorySignals;
import com.kholodilin.repogrowth.collection.planner.PlanningWindow;
import com.kholodilin.repogrowth.common.api.ApiException;
import com.kholodilin.repogrowth.common.observability.AppMetrics;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RepositoryService {

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private com.kholodilin.repogrowth.orchestration.CollectionRequestService commands;

  private final GitHubOwnerJdbcRepository ownerRepository;
  private final RepositoryJdbcRepository repositoryJdbcRepository;
  private final AppMetrics metrics;
  private final PlanningWindow planningWindow;

  public RepositoryService(
      GitHubOwnerJdbcRepository ownerRepository,
      RepositoryJdbcRepository repositoryJdbcRepository,
      AppMetrics metrics,
      PlanningWindow planningWindow) {
    this.ownerRepository = ownerRepository;
    this.repositoryJdbcRepository = repositoryJdbcRepository;
    this.metrics = metrics;
    this.planningWindow = planningWindow;
  }

  @Transactional
  public List<Repository> list(boolean refresh) {
    if (refresh) {
      throw ApiException.validation(
          commands != null
              ? "Use POST /api/v1/admin/repositories/refresh"
              : "Collector orchestration is required to refresh repositories");
    }
    List<Repository> repositories = repositoryJdbcRepository.findListed();
    metrics.setTrackedRepositories(repositoryJdbcRepository.countTracked());
    return repositories;
  }

  public Map<Long, RepositorySignals> signalsFor(Collection<Long> ids) {
    return repositoryJdbcRepository.signalsFor(ids);
  }

  public Repository get(long id) {
    return repositoryJdbcRepository
        .findById(id)
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
    if (enabled && commands == null) {
      throw ApiException.serviceUnavailable("Collector orchestration is required to track");
    }
    if (enabled && !repositoryJdbcRepository.isAccountAccessible(id)) {
      throw ApiException.validation(
          "Only repositories available to the GitHub token can be tracked");
    }
    Repository updated = repositoryJdbcRepository.setTracking(id, enabled);
    metrics.setTrackedRepositories(repositoryJdbcRepository.countTracked());
    if (enabled) {
      commands.manual(id, planningWindow.businessDate());
    }
    return updated;
  }

  public List<Repository> tracked() {
    return repositoryJdbcRepository.findTracked();
  }

  public List<String> topics(long repositoryId) {
    return repositoryJdbcRepository.findTopics(repositoryId);
  }
}
