package com.kholodilin.repogrowth.topics.controller;

import com.kholodilin.repogrowth.administration.service.PublicationService;
import com.kholodilin.repogrowth.catalog.model.Repository;
import com.kholodilin.repogrowth.catalog.service.RepositoryService;
import com.kholodilin.repogrowth.common.api.ApiException;
import com.kholodilin.repogrowth.common.api.ApiSurface;
import com.kholodilin.repogrowth.identity.security.ResourceAccessPolicy;
import com.kholodilin.repogrowth.search.service.CompetitorCardService;
import com.kholodilin.repogrowth.topics.model.TopicWatch;
import com.kholodilin.repogrowth.topics.service.TopicCompetitorService;
import com.kholodilin.repogrowth.topics.service.TopicVisibilityService;
import com.kholodilin.repogrowth.topics.service.TopicVisibilityService.TopicHistory;
import com.kholodilin.repogrowth.topics.service.TopicVisibilityService.TopicRunResults;
import com.kholodilin.repogrowth.topics.service.TopicsKpiResponse;
import com.kholodilin.repogrowth.topics.service.TopicsKpiService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unified Topics resource API ({@link ApiSurface#RESOURCE}). Legacy {@code topics-visibility}
 * remains until charts switch to include=history / KPI endpoints.
 */
@RestController
@RequestMapping("/api/v1")
public class TopicsResourceController {

  private final ResourceAccessPolicy access;
  private final RepositoryService repositories;
  private final TopicsKpiService kpi;
  private final TopicVisibilityService topics;
  private final TopicCompetitorService topicCompetitors;

  public TopicsResourceController(
      ResourceAccessPolicy access,
      RepositoryService repositories,
      TopicsKpiService kpi,
      TopicVisibilityService topics,
      TopicCompetitorService topicCompetitors) {
    this.access = access;
    this.repositories = repositories;
    this.kpi = kpi;
    this.topics = topics;
    this.topicCompetitors = topicCompetitors;
  }

  @GetMapping("/owners/{ownerId}/topics/kpi")
  public TopicsKpiResponse ownerTopicsKpi(
      @PathVariable long ownerId,
      @RequestParam(defaultValue = "7d") String period,
      @RequestParam(defaultValue = "all") String scope) {
    return kpi.ownerKpi(ownerId, readableOwnerRepositories(ownerId), period, scope);
  }

  @GetMapping("/repositories/{repositoryId}/topics/kpi")
  public TopicsKpiResponse repositoryTopicsKpi(
      @PathVariable long repositoryId,
      @RequestParam(defaultValue = "7d") String period,
      @RequestParam(defaultValue = "all") String scope) {
    access.requireReadable(repositoryId, PublicationService.Module.TOPICS);
    return kpi.repositoryKpi(repositoryId, period, scope);
  }

  @GetMapping("/repositories/{repositoryId}/topic-watches")
  public List<TopicHistory> listWatches(
      @PathVariable long repositoryId,
      @RequestParam(defaultValue = "all") String scope,
      @RequestParam(required = false) String include,
      @RequestParam(defaultValue = "50") int limit) {
    access.requireReadable(repositoryId, PublicationService.Module.TOPICS);
    boolean withHistory = include != null && include.toLowerCase().contains("history");
    return kpi.repositoryWatchesWithHistory(repositoryId, scope, withHistory, limit);
  }

  @GetMapping("/topic-watches/{watchId}")
  public TopicWatch get(@PathVariable long watchId) {
    TopicWatch watch = requireWatch(watchId);
    access.requireReadable(watch.repositoryId(), PublicationService.Module.TOPICS);
    return watch;
  }

  @GetMapping("/topic-watches/{watchId}/history")
  public TopicHistory history(@PathVariable long watchId) {
    TopicWatch watch = requireWatch(watchId);
    access.requireReadable(watch.repositoryId(), PublicationService.Module.TOPICS);
    return topics.history(watchId);
  }

  @GetMapping("/topic-watches/{watchId}/results")
  public TopicRunResults results(@PathVariable long watchId) {
    TopicWatch watch = requireWatch(watchId);
    access.requireReadable(watch.repositoryId(), PublicationService.Module.TOPICS);
    return topics.latestResults(watchId);
  }

  @GetMapping("/topic-watches/{watchId}/series")
  public List<TopicCompetitorService.RankSeries> series(@PathVariable long watchId) {
    TopicWatch watch = requireWatch(watchId);
    access.requireReadable(watch.repositoryId(), PublicationService.Module.TOPICS);
    return topicCompetitors.series(watch.repositoryId(), watch.topic(), scopeOf(watch));
  }

  @GetMapping("/topic-watches/{watchId}/competitors/{githubRepositoryId}")
  public CompetitorCardService.CompetitorCard competitor(
      @PathVariable long watchId, @PathVariable long githubRepositoryId) {
    TopicWatch watch = requireWatch(watchId);
    access.requireReadable(watch.repositoryId(), PublicationService.Module.TOPICS);
    return topicCompetitors.card(
        watch.repositoryId(), watch.topic(), scopeOf(watch), githubRepositoryId);
  }

  @GetMapping("/topic-runs/{runId}/results")
  public TopicRunResults runResults(@PathVariable long runId) {
    TopicRunResults results = topics.results(runId);
    access.requireReadable(results.watch().repositoryId(), PublicationService.Module.TOPICS);
    return results;
  }

  private TopicWatch requireWatch(long watchId) {
    return topics.getWatch(watchId);
  }

  private List<Long> readableOwnerRepositories(long ownerId) {
    repositories.owner(ownerId);
    List<Long> readable =
        access.filterReadable(
            repositories.list(false).stream()
                .filter(repo -> repo.ownerId() == ownerId)
                .map(Repository::id)
                .toList(),
            PublicationService.Module.TOPICS);
    if (readable.isEmpty()) {
      throw ApiException.notFound("Owner not found");
    }
    return readable;
  }

  private static String scopeOf(TopicWatch watch) {
    return watch.allLanguages() ? "all" : "language";
  }
}
