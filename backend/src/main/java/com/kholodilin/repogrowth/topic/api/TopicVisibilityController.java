package com.kholodilin.repogrowth.topic.api;

import com.kholodilin.repogrowth.topic.application.TopicVisibilityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class TopicVisibilityController {

    private final TopicVisibilityService topicVisibilityService;

    public TopicVisibilityController(TopicVisibilityService topicVisibilityService) {
        this.topicVisibilityService = topicVisibilityService;
    }

    @GetMapping("/repositories/{id}/topics-visibility")
    public List<TopicVisibilityService.TopicHistory> visibility(
            @PathVariable long id,
            @RequestParam(defaultValue = "all") String scope
    ) {
        return topicVisibilityService.visibility(id, scope);
    }

    @PostMapping("/repositories/{id}/topic-watches/run")
    public RunAllAccepted runAll(
            @PathVariable long id,
            @RequestParam(defaultValue = "all") String scope
    ) {
        return new RunAllAccepted(topicVisibilityService.runAll(id, scope));
    }

    @PostMapping("/topic-watches/{id}/run")
    public RunAccepted run(@PathVariable long id) {
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

    @GetMapping("/topic-runs/{id}/results")
    public TopicVisibilityService.TopicRunResults results(@PathVariable long id) {
        return topicVisibilityService.results(id);
    }

    public record RunAccepted(long topicRunId) {
    }

    public record RunAllAccepted(List<Long> topicRunIds) {
    }
}
