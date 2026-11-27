package com.kholodilin.repogrowth.topics.controller;

import com.kholodilin.repogrowth.search.service.CompetitorCardService;
import com.kholodilin.repogrowth.topics.service.TopicCompetitorService;
import com.kholodilin.repogrowth.topics.service.TopicVisibilityService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Topic visibility and by-topic chart helpers. Watch CRUD/run/history by id live on resource
 * controllers — no {@code /admin} prefix.
 */
@RestController
@RequestMapping("/api/v1")
public class TopicVisibilityController {

  private final TopicVisibilityService topicVisibilityService;
  private final TopicCompetitorService topicCompetitorService;

  public TopicVisibilityController(
      TopicVisibilityService topicVisibilityService,
      TopicCompetitorService topicCompetitorService) {
    this.topicVisibilityService = topicVisibilityService;
    this.topicCompetitorService = topicCompetitorService;
  }

  @GetMapping("/repositories/{id}/topics-visibility")
  public List<TopicVisibilityService.TopicHistory> visibility(
      @PathVariable long id, @RequestParam(defaultValue = "all") String scope) {
    return topicVisibilityService.visibility(id, scope);
  }

  @GetMapping("/repositories/{id}/topics/{topic}/history")
  public TopicVisibilityService.TopicHistory historyByTopic(
      @PathVariable long id,
      @PathVariable String topic,
      @RequestParam(defaultValue = "all") String scope) {
    return topicVisibilityService.historyByTopic(id, topic, scope);
  }

  @GetMapping("/repositories/{id}/topics/{topic}/results")
  public TopicVisibilityService.TopicRunResults resultsByTopic(
      @PathVariable long id,
      @PathVariable String topic,
      @RequestParam(defaultValue = "all") String scope) {
    return topicVisibilityService.latestResultsByTopic(id, topic, scope);
  }

  @GetMapping("/repositories/{id}/topics/{topic}/series")
  public List<TopicCompetitorService.RankSeries> series(
      @PathVariable long id,
      @PathVariable String topic,
      @RequestParam(defaultValue = "all") String scope) {
    return topicCompetitorService.series(id, topic, scope);
  }

  @GetMapping("/repositories/{id}/topics/{topic}/competitors/{githubRepositoryId}")
  public CompetitorCardService.CompetitorCard competitor(
      @PathVariable long id,
      @PathVariable String topic,
      @PathVariable long githubRepositoryId,
      @RequestParam(defaultValue = "all") String scope) {
    return topicCompetitorService.card(id, topic, scope, githubRepositoryId);
  }
}
