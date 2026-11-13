package com.kholodilin.repogrowth.topic.api;

import com.kholodilin.repogrowth.search.application.CompetitorCardService;
import com.kholodilin.repogrowth.topic.application.TopicCompetitorService;
import com.kholodilin.repogrowth.topic.application.TopicVisibilityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin")
public class TopicVisibilityController {

    @org.springframework.beans.factory.annotation.Autowired(required=false) private com.kholodilin.repogrowth.orchestration.CollectionRequestService commands;
    @org.springframework.beans.factory.annotation.Autowired private com.kholodilin.repogrowth.collection.planner.PlanningWindow commandWindow;
    private final TopicVisibilityService topicVisibilityService;
    private final TopicCompetitorService topicCompetitorService;

    public TopicVisibilityController(
            TopicVisibilityService topicVisibilityService,
            TopicCompetitorService topicCompetitorService
    ) {
        this.topicVisibilityService = topicVisibilityService;
        this.topicCompetitorService = topicCompetitorService;
    }

    @GetMapping("/repositories/{id}/topics-visibility")
    public List<TopicVisibilityService.TopicHistory> visibility(
            @PathVariable long id,
            @RequestParam(defaultValue = "all") String scope
    ) {
        return topicVisibilityService.visibility(id, scope);
    }

    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    @PostMapping("/repositories/{id}/topic-watches/run")
    public Object runAll(
            @PathVariable long id,
            @RequestParam(defaultValue = "all") String scope
    ) {
        if(commands!=null)return commands.topicsCommand(id,scope,commandWindow.businessDate());
        return new RunAllAccepted(topicVisibilityService.runAll(id, scope));
    }

    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    @PostMapping("/topic-watches/{id}/run")
    public Object run(@PathVariable long id) {
        if(commands!=null)return commands.topicCommand(id,commandWindow.businessDate());
        return new RunAccepted(topicVisibilityService.runNow(id));
    }

    @GetMapping("/topic-watches/{id}/history")
    public TopicVisibilityService.TopicHistory history(@PathVariable long id) {
        return topicVisibilityService.history(id);
    }

    @GetMapping("/topic-watches/{id}/results")
    public TopicVisibilityService.TopicRunResults latestResults(@PathVariable long id) {
        return topicVisibilityService.latestResults(id);
    }

    @GetMapping("/repositories/{id}/topics/{topic}/history")
    public TopicVisibilityService.TopicHistory historyByTopic(
            @PathVariable long id,
            @PathVariable String topic,
            @RequestParam(defaultValue = "all") String scope
    ) {
        return topicVisibilityService.historyByTopic(id, topic, scope);
    }

    @GetMapping("/repositories/{id}/topics/{topic}/results")
    public TopicVisibilityService.TopicRunResults resultsByTopic(
            @PathVariable long id,
            @PathVariable String topic,
            @RequestParam(defaultValue = "all") String scope
    ) {
        return topicVisibilityService.latestResultsByTopic(id, topic, scope);
    }

    @GetMapping("/repositories/{id}/topics/{topic}/series")
    public List<TopicCompetitorService.RankSeries> series(
            @PathVariable long id, @PathVariable String topic,
            @RequestParam(defaultValue = "all") String scope
    ) {
        return topicCompetitorService.series(id, topic, scope);
    }

    @GetMapping("/repositories/{id}/topics/{topic}/competitors/{githubRepositoryId}")
    public CompetitorCardService.CompetitorCard competitor(
            @PathVariable long id,
            @PathVariable String topic,
            @PathVariable long githubRepositoryId,
            @RequestParam(defaultValue = "all") String scope
    ) {
        return topicCompetitorService.card(id, topic, scope, githubRepositoryId);
    }

    @GetMapping("/topic-runs/{id}/results")
    public TopicVisibilityService.TopicRunResults results(@PathVariable long id) {
        return topicVisibilityService.results(id);
    }

    public record RunAccepted(long topicRunId) {
    }

    public record RunAllAccepted(List<Long> topicRunIds) {
    }
}
