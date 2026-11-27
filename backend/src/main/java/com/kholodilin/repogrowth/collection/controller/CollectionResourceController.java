package com.kholodilin.repogrowth.collection.controller;

import com.kholodilin.collector.contracts.CollectorHttpHeaders;
import com.kholodilin.repogrowth.administration.service.PublicationService;
import com.kholodilin.repogrowth.collection.planner.PlanningWindow;
import com.kholodilin.repogrowth.collection.service.CollectionRequestService;
import com.kholodilin.repogrowth.collection.service.IdempotentCollectionRequests;
import com.kholodilin.repogrowth.common.api.ApiException;
import com.kholodilin.repogrowth.common.api.ApiSurface;
import com.kholodilin.repogrowth.identity.security.AppUserPrincipal;
import com.kholodilin.repogrowth.identity.security.ResourceAccessPolicy;
import com.kholodilin.repogrowth.search.model.SearchQuery;
import com.kholodilin.repogrowth.search.service.SearchQueryService;
import com.kholodilin.repogrowth.topics.model.TopicWatch;
import com.kholodilin.repogrowth.topics.repository.TopicWatchJdbcRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Resource-surface collection commands ({@link ApiSurface#RESOURCE}). Mutations require admin;
 * Idempotency-Key is mandatory for repository collections.
 */
@RestController
@RequestMapping("/api/v1")
@ConditionalOnBean(IdempotentCollectionRequests.class)
public class CollectionResourceController {

  private final ResourceAccessPolicy access;
  private final IdempotentCollectionRequests idempotent;
  private final PlanningWindow window;
  private final SearchQueryService searchQueries;
  private final TopicWatchJdbcRepository watches;

  @Autowired(required = false)
  private CollectionRequestService commands;

  public CollectionResourceController(
      ResourceAccessPolicy access,
      IdempotentCollectionRequests idempotent,
      PlanningWindow window,
      SearchQueryService searchQueries,
      TopicWatchJdbcRepository watches) {
    this.access = access;
    this.idempotent = idempotent;
    this.window = window;
    this.searchQueries = searchQueries;
    this.watches = watches;
  }

  @PostMapping("/repositories/{repositoryId}/collections")
  public ResponseEntity<CollectionRequestService.Created> collect(
      @PathVariable long repositoryId,
      @RequestHeader(CollectorHttpHeaders.IDEMPOTENCY_KEY) String key,
      Authentication authentication) {
    access.requireAction(
        repositoryId,
        ResourceAccessPolicy.ResourceAction.MUTATE,
        PublicationService.Module.DASHBOARD);
    long actorId = ((AppUserPrincipal) authentication.getPrincipal()).userId();
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(idempotent.create(actorId, key, repositoryId, window.businessDate()));
  }

  @PostMapping("/search-queries/{queryId}/run")
  public Object runSearchQuery(@PathVariable long queryId) {
    SearchQuery query = searchQueries.getQuery(queryId);
    access.requireAction(
        query.repositoryId(),
        ResourceAccessPolicy.ResourceAction.MUTATE,
        PublicationService.Module.DASHBOARD);
    return requireCommands().searchCommand(queryId, window.businessDate());
  }

  @PostMapping("/repositories/{repositoryId}/search-queries/run")
  public Object runRepositorySearchQueries(@PathVariable long repositoryId) {
    access.requireAction(
        repositoryId,
        ResourceAccessPolicy.ResourceAction.MUTATE,
        PublicationService.Module.DASHBOARD);
    return requireCommands().searchAllCommand(repositoryId, window.businessDate());
  }

  @PostMapping("/topic-watches/{watchId}/run")
  public Object runTopicWatch(@PathVariable long watchId) {
    TopicWatch watch =
        watches.findById(watchId).orElseThrow(() -> ApiException.notFound("Topic watch not found"));
    access.requireAction(
        watch.repositoryId(),
        ResourceAccessPolicy.ResourceAction.MUTATE,
        PublicationService.Module.TOPICS);
    return requireCommands().topicCommand(watchId, window.businessDate());
  }

  @PostMapping("/repositories/{repositoryId}/topic-watches/run")
  public Object runRepositoryTopicWatches(
      @PathVariable long repositoryId, @RequestParam(defaultValue = "all") String scope) {
    access.requireAction(
        repositoryId, ResourceAccessPolicy.ResourceAction.MUTATE, PublicationService.Module.TOPICS);
    return requireCommands().topicsCommand(repositoryId, scope, window.businessDate());
  }

  private CollectionRequestService requireCommands() {
    if (commands == null) {
      throw ApiException.serviceUnavailable("Collector orchestration is required");
    }
    return commands;
  }
}
