package com.kholodilin.repogrowth.topic.api;

import com.kholodilin.repogrowth.auth.security.ResourceAccessPolicy;
import com.kholodilin.repogrowth.common.api.ApiSurface;
import com.kholodilin.repogrowth.publication.PublicationService;
import com.kholodilin.repogrowth.search.application.CompetitorCardService;
import com.kholodilin.repogrowth.topic.application.TopicCompetitorService;
import com.kholodilin.repogrowth.topic.application.TopicVisibilityService;
import com.kholodilin.repogrowth.topic.application.TopicVisibilityService.TopicHistory;
import com.kholodilin.repogrowth.topic.application.TopicVisibilityService.TopicRunResults;
import com.kholodilin.repogrowth.topic.application.TopicsKpiService;
import com.kholodilin.repogrowth.topic.domain.TopicWatch;
import com.kholodilin.repogrowth.topic.persistence.TopicWatchJdbcRepository;
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
  private final TopicsKpiService kpi;
  private final TopicVisibilityService topics;
  private final TopicCompetitorService topicCompetitors;
  private final TopicWatchJdbcRepository watches;

  public TopicsResourceController(
      ResourceAccessPolicy access,
      TopicsKpiService kpi,
      TopicVisibilityService topics,
      TopicCompetitorService topicCompetitors,
      TopicWatchJdbcRepository watches) {
    this.access = access;
    this.kpi = kpi;
    this.topics = topics;
    this.topicCompetitors = topicCompetitors;
    this.watches = watches;
  }

  @GetMapping("/owners/{ownerId}/topics/kpi")
  public TopicsKpiResponse ownerTopicsKpi(
      @PathVariable long ownerId,
      @RequestParam(defaultValue = "7d") String period,
      @RequestParam(defaultValue = "all") String scope) {
    return kpi.ownerKpi(ownerId, period, scope);
  }

  @GetMapping("/repositories/{repositoryId}/topics/kpi")
  public TopicsKpiResponse repositoryTopicsKpi(
      @PathVariable long repositoryId,
      @RequestParam(defaultValue = "7d") String period,
      @RequestParam(defaultValue = "all") String scope) {
    return kpi.repositoryKpi(repositoryId, period, scope);
  }

  @GetMapping("/repositories/{repositoryId}/topic-watches")
  public List<TopicHistory> listWatches(
      @PathVariable long repositoryId,
      @RequestParam(defaultValue = "all") String scope,
      @RequestParam(required = false) String include,
      @RequestParam(defaultValue = "50") int limit) {
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
    return watches
        .findById(watchId)
        .orElseThrow(() ->
            com.kholodilin.repogrowth.common.api.ApiException.notFound("Topic watch not found"));
  }

  private static String scopeOf(TopicWatch watch) {
    return watch.allLanguages() ? "all" : "language";
  }
}
