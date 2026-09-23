package com.kholodilin.repogrowth.common.api;

import com.kholodilin.repogrowth.collection.api.CollectionController;
import com.kholodilin.repogrowth.collection.domain.CollectionJob;
import com.kholodilin.repogrowth.collection.domain.CollectionJobStatus;
import com.kholodilin.repogrowth.collection.domain.CollectionJobType;
import com.kholodilin.repogrowth.collection.domain.CollectionRun;
import com.kholodilin.repogrowth.collection.domain.CollectionRunStatus;
import com.kholodilin.repogrowth.collection.persistence.CollectionJobJdbcRepository;
import com.kholodilin.repogrowth.collection.persistence.CollectionRunJdbcRepository;
import com.kholodilin.repogrowth.collection.planner.CollectionPlanner;
import com.kholodilin.repogrowth.collection.planner.DailyPlanner;
import com.kholodilin.repogrowth.collection.planner.PlanningWindow;
import com.kholodilin.repogrowth.common.web.SpaController;
import com.kholodilin.repogrowth.event.api.GrowthEventController;
import com.kholodilin.repogrowth.event.application.GrowthEventService;
import com.kholodilin.repogrowth.event.domain.GrowthEvent;
import com.kholodilin.repogrowth.event.domain.GrowthEventSetting;
import com.kholodilin.repogrowth.github.exception.GitHubException;
import com.kholodilin.repogrowth.repository.api.HealthCheckItem;
import com.kholodilin.repogrowth.repository.api.RepositoryController;
import com.kholodilin.repogrowth.repository.api.RepositoryHealthResponse;
import com.kholodilin.repogrowth.repository.application.RepositoryHealthService;
import com.kholodilin.repogrowth.repository.application.RepositoryService;
import com.kholodilin.repogrowth.repository.domain.GitHubOwner;
import com.kholodilin.repogrowth.repository.domain.OwnerType;
import com.kholodilin.repogrowth.repository.domain.Repository;
import com.kholodilin.repogrowth.search.api.SearchQueryController;
import com.kholodilin.repogrowth.search.application.ActivityClassifier;
import com.kholodilin.repogrowth.search.application.SearchQueryService;
import com.kholodilin.repogrowth.search.domain.ActivityStatus;
import com.kholodilin.repogrowth.search.domain.SearchQuery;
import com.kholodilin.repogrowth.search.domain.SearchRun;
import com.kholodilin.repogrowth.search.domain.SearchRunStatus;
import com.kholodilin.repogrowth.topic.api.TopicVisibilityController;
import com.kholodilin.repogrowth.topic.application.TopicVisibilityService;
import com.kholodilin.repogrowth.topic.domain.TopicRun;
import com.kholodilin.repogrowth.topic.domain.TopicWatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {
        RepositoryController.class,
        CollectionController.class,
        SearchQueryController.class,
        GrowthEventController.class,
        TopicVisibilityController.class,
        SpaController.class
})
class ApiControllersWebMvcTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    RepositoryService repositoryService;
    @MockitoBean
    RepositoryHealthService repositoryHealthService;
    @MockitoBean
    ActivityClassifier activityClassifier;
    @MockitoBean
    CollectionPlanner collectionPlanner;
    @MockitoBean
    PlanningWindow planningWindow;
    @MockitoBean
    DailyPlanner dailyPlanner;
    @MockitoBean
    CollectionRunJdbcRepository runRepository;
    @MockitoBean
    CollectionJobJdbcRepository jobRepository;
    @MockitoBean
    SearchQueryService searchQueryService;
    @MockitoBean
    GrowthEventService growthEventService;
    @MockitoBean
    TopicVisibilityService topicVisibilityService;

    private Repository repository;
    private SearchQuery query;
    private TopicWatch watch;

    @BeforeEach
    void stubs() {
        Instant now = Instant.parse("2026-09-23T06:00:00Z");
        repository = new Repository(
                13L, 99L, 1L, "outbox", "acme/outbox", "desc", "PUBLIC", "main", "Java",
                false, false, 21, 0, 16, 1, 7, true,
                now, now, now, now, null, null, now, now
        );
        query = new SearchQuery(5L, 13L, "outbox", "outbox language:java", true, 50, now, now);
        watch = new TopicWatch(7L, 13L, "outbox", null, "stars", "desc", true, 50, now, now);
        GitHubOwner owner = new GitHubOwner(1L, 1L, "acme", OwnerType.USER, null, "https://github.com/acme", now, now);
        when(repositoryService.list(false)).thenReturn(List.of(repository));
        when(repositoryService.get(13L)).thenReturn(repository);
        when(repositoryService.setTracking(13L, true)).thenReturn(repository);
        when(repositoryService.owner(1L)).thenReturn(owner);
        when(repositoryService.topics(13L)).thenReturn(List.of("kafka"));
        when(repositoryHealthService.forRepository(any())).thenReturn(new RepositoryHealthResponse(
                List.of(new HealthCheckItem("Description", true)),
                List.of()
        ));
        when(activityClassifier.classify(anyBoolean(), any())).thenReturn(ActivityStatus.ACTIVE);
        when(planningWindow.businessDate()).thenReturn(LocalDate.of(2026, 9, 23));
        CollectionRun run = new CollectionRun(1L, 13L, LocalDate.of(2026, 9, 23), CollectionRunStatus.SUCCESS, 1, 1, 0, now, now);
        when(collectionPlanner.planRepository(eq(13L), any(), eq(true))).thenReturn(run);
        when(runRepository.findById(1L)).thenReturn(Optional.of(run));
        when(jobRepository.findByRun(1L)).thenReturn(List.of(new CollectionJob(
                2L, 1L, 13L, CollectionJobType.TRAFFIC, LocalDate.of(2026, 9, 23),
                CollectionJobStatus.SUCCESS, 1, null, null, null, now, now, null, null
        )));
        when(dailyPlanner.planNow(true)).thenReturn(new DailyPlanner.PlannerResult(true, 1, 0, 0, "2026-09-23"));
        when(searchQueryService.list(13L)).thenReturn(List.of(query));
        when(searchQueryService.create(eq(13L), any(), any(), any(), any())).thenReturn(query);
        when(searchQueryService.update(eq(5L), any(), any(), any(), any())).thenReturn(query);
        when(searchQueryService.getQuery(5L)).thenReturn(query);
        when(searchQueryService.runNow(5L)).thenReturn(9L);
        when(searchQueryService.runAll(13L)).thenReturn(List.of(9L));
        SearchRun searchRun = new SearchRun(
                9L, 5L, 13L, LocalDate.of(2026, 9, 23), SearchRunStatus.SUCCESS, 1,
                null, null, null, now, now, now, 80, 4, "SUCCESS", null, null
        );
        SearchQueryService.SearchRunResults results = new SearchQueryService.SearchRunResults(searchRun, query, List.of());
        when(searchQueryService.history(5L)).thenReturn(new SearchQueryService.SearchHistory(
                query, 4, null, 1, 2, 2, List.of(), now, "SUCCESS", "SUCCESS", 80, List.of()
        ));
        when(searchQueryService.visibility(13L)).thenReturn(List.of());
        when(searchQueryService.latestResults(5L)).thenReturn(results);
        when(searchQueryService.results(9L)).thenReturn(results);
        GrowthEvent event = new GrowthEvent(1L, 13L, now, "PROMOTION", "LINKEDIN_POST", "Posted", null, null, "MANUAL", null, now, now);
        when(growthEventService.list(eq(13L), anyString())).thenReturn(List.of(event));
        when(growthEventService.createManual(eq(13L), any(), any(), any(), any(), any())).thenReturn(event);
        when(growthEventService.updateManual(eq(1L), any(), any(), any(), any(), any())).thenReturn(event);
        when(growthEventService.settings(13L)).thenReturn(List.of(new GrowthEventSetting(13L, "STAR_MILESTONE", true, now, now)));
        when(growthEventService.updateSettings(eq(13L), any())).thenReturn(List.of(new GrowthEventSetting(13L, "STAR_MILESTONE", false, now, now)));
        TopicRun topicRun = new TopicRun(
                3L, 7L, 13L, LocalDate.of(2026, 9, 23), SearchRunStatus.SUCCESS, 1,
                null, null, null, now, now, now, 170, 24, null, null
        );
        TopicVisibilityService.TopicHistory history = new TopicVisibilityService.TopicHistory(
                watch, 24, null, null, null, 24, List.of(), now, "SUCCESS", 170, List.of()
        );
        TopicVisibilityService.TopicRunResults topicResults = new TopicVisibilityService.TopicRunResults(topicRun, watch, List.of());
        when(topicVisibilityService.visibility(eq(13L), anyString())).thenReturn(List.of(history));
        when(topicVisibilityService.runAll(eq(13L), anyString())).thenReturn(List.of(3L));
        when(topicVisibilityService.runNow(7L)).thenReturn(3L);
        when(topicVisibilityService.history(7L)).thenReturn(history);
        when(topicVisibilityService.latestResults(7L)).thenReturn(topicResults);
        when(topicVisibilityService.historyByTopic(eq(13L), eq("outbox"), anyString())).thenReturn(history);
        when(topicVisibilityService.latestResultsByTopic(eq(13L), eq("outbox"), anyString())).thenReturn(topicResults);
        when(topicVisibilityService.results(3L)).thenReturn(topicResults);
    }

    @Test
    void repositoryCollectionSearchEventAndTopicEndpoints() throws Exception {
        mockMvc.perform(get("/api/v1/repositories")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fullName").value("acme/outbox"));
        mockMvc.perform(get("/api/v1/repositories/13")).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/repositories/13/tracking")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/repositories/13/collect")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
        mockMvc.perform(post("/api/v1/collection/plan")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/collection-runs/1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.jobs[0].jobType").value("TRAFFIC"));
        mockMvc.perform(get("/api/v1/repositories/13/search-queries")).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/repositories/13/search-queries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"q\",\"query\":\"outbox\",\"enabled\":true,\"resultLimit\":50}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/v1/search-queries/5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"q\",\"query\":\"outbox\",\"enabled\":true,\"resultLimit\":50}"))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/search-queries/5")).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/search-queries/5/run")).andExpect(status().isOk())
                .andExpect(jsonPath("$.searchRunId").value(9));
        mockMvc.perform(post("/api/v1/repositories/13/search-queries/run")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/search-queries/5")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/search-queries/5/history")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/repositories/13/search-visibility")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/search-queries/5/results")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/search-runs/9/results")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/repositories/13/growth-events")).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/repositories/13/growth-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"CUSTOM\",\"eventAt\":\"2026-09-22T12:00:00Z\",\"title\":\"Posted\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/v1/growth-events/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"CUSTOM\",\"eventAt\":\"2026-09-22T12:00:00Z\",\"title\":\"Updated\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/growth-events/1")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/repositories/13/growth-event-settings")).andExpect(status().isOk());
        mockMvc.perform(put("/api/v1/repositories/13/growth-event-settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"eventType\":\"STAR_MILESTONE\",\"enabled\":false}]"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/repositories/13/topics-visibility")).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/repositories/13/topic-watches/run")).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/topic-watches/7/run")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/topic-watches/7/history")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/topic-watches/7/results")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/repositories/13/topics/outbox/history")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/repositories/13/topics/outbox/results")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/topic-runs/3/results")).andExpect(status().isOk());
        mockMvc.perform(get("/dashboard")).andExpect(status().isNotFound());
    }

    @Test
    void mapsApiAndGitHubErrors() throws Exception {
        when(repositoryService.get(8L)).thenThrow(ApiException.notFound("missing"));
        mockMvc.perform(get("/api/v1/repositories/8")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        when(runRepository.findById(99L)).thenReturn(Optional.empty());
        mockMvc.perform(get("/api/v1/collection-runs/99")).andExpect(status().isNotFound());

        doThrow(GitHubException.auth(401, "bad token")).when(repositoryService).list(true);
        mockMvc.perform(get("/api/v1/repositories?refresh=true")).andExpect(status().isUnauthorized());
        doThrow(GitHubException.rateLimit(Instant.parse("2026-09-23T07:00:00Z"), "slow")).when(repositoryService).list(true);
        mockMvc.perform(get("/api/v1/repositories?refresh=true")).andExpect(status().isTooManyRequests());
        doThrow(GitHubException.notFound("gone")).when(repositoryService).list(true);
        mockMvc.perform(get("/api/v1/repositories?refresh=true")).andExpect(status().isNotFound());
        doThrow(GitHubException.validation("bad")).when(repositoryService).list(true);
        mockMvc.perform(get("/api/v1/repositories?refresh=true")).andExpect(status().isBadRequest());
        doThrow(GitHubException.api(502, true, null, "down")).when(repositoryService).list(true);
        mockMvc.perform(get("/api/v1/repositories?refresh=true")).andExpect(status().isBadGateway());

        mockMvc.perform(post("/api/v1/repositories/13/tracking")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/repositories/13/tracking")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest());
        doThrow(new IllegalStateException("boom")).when(repositoryService).get(13L);
        mockMvc.perform(get("/api/v1/repositories/13")).andExpect(status().isInternalServerError());
    }
}
