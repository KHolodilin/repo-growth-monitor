package com.kholodilin.repogrowth.common.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kholodilin.repogrowth.administration.controller.RepositoryController;
import com.kholodilin.repogrowth.administration.service.PublicationService;
import com.kholodilin.repogrowth.catalog.controller.HealthCheckItem;
import com.kholodilin.repogrowth.catalog.controller.PortfolioDashboardController;
import com.kholodilin.repogrowth.catalog.controller.RepositoryHealthResponse;
import com.kholodilin.repogrowth.catalog.model.ActivityStatus;
import com.kholodilin.repogrowth.catalog.model.GitHubOwner;
import com.kholodilin.repogrowth.catalog.model.OwnerType;
import com.kholodilin.repogrowth.catalog.model.Repository;
import com.kholodilin.repogrowth.catalog.model.RepositorySignals;
import com.kholodilin.repogrowth.catalog.service.ActivityClassifier;
import com.kholodilin.repogrowth.catalog.service.PortfolioDashboardService;
import com.kholodilin.repogrowth.catalog.service.RepositoryHealthService;
import com.kholodilin.repogrowth.catalog.service.RepositoryService;
import com.kholodilin.repogrowth.collection.controller.CollectionController;
import com.kholodilin.repogrowth.collection.controller.SystemStatusController;
import com.kholodilin.repogrowth.collection.model.CollectionJob;
import com.kholodilin.repogrowth.collection.model.CollectionJobStatus;
import com.kholodilin.repogrowth.collection.model.CollectionJobType;
import com.kholodilin.repogrowth.collection.model.CollectionRun;
import com.kholodilin.repogrowth.collection.model.CollectionRunStatus;
import com.kholodilin.repogrowth.collection.planner.PlanningWindow;
import com.kholodilin.repogrowth.collection.repository.CollectionJobJdbcRepository;
import com.kholodilin.repogrowth.collection.repository.CollectionRunJdbcRepository;
import com.kholodilin.repogrowth.collection.service.CollectionRequestService;
import com.kholodilin.repogrowth.collection.service.CollectionRunMapper;
import com.kholodilin.repogrowth.collection.service.CollectionRunQueryService;
import com.kholodilin.repogrowth.common.web.SpaController;
import com.kholodilin.repogrowth.credentials.service.GitHubAccessStatusService;
import com.kholodilin.repogrowth.events.controller.EventsResourceController;
import com.kholodilin.repogrowth.events.model.GrowthEvent;
import com.kholodilin.repogrowth.events.model.GrowthEventSetting;
import com.kholodilin.repogrowth.events.service.GrowthEventService;
import com.kholodilin.repogrowth.identity.security.CurrentUserAccess;
import com.kholodilin.repogrowth.identity.security.ResourceAccessPolicy;
import com.kholodilin.repogrowth.search.controller.SearchQueryController;
import com.kholodilin.repogrowth.search.model.SearchQuery;
import com.kholodilin.repogrowth.search.model.SearchRun;
import com.kholodilin.repogrowth.search.model.SearchRunStatus;
import com.kholodilin.repogrowth.search.service.CompetitorCardService;
import com.kholodilin.repogrowth.search.service.SearchKpiService;
import com.kholodilin.repogrowth.search.service.SearchQueryService;
import com.kholodilin.repogrowth.stats.service.AdminStatsHistoryService;
import com.kholodilin.repogrowth.support.WebMvcTestSecurityConfig;
import com.kholodilin.repogrowth.topics.controller.TopicVisibilityController;
import com.kholodilin.repogrowth.topics.model.TopicRun;
import com.kholodilin.repogrowth.topics.model.TopicWatch;
import com.kholodilin.repogrowth.topics.service.TopicCompetitorService;
import com.kholodilin.repogrowth.topics.service.TopicVisibilityService;
import com.kholodilin.repogrowth.topics.service.TopicsKpiService;
import com.kholodilin.repogrowth.traffic.service.AdminTrafficService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
    controllers = {
      RepositoryController.class,
      CollectionController.class,
      com.kholodilin.repogrowth.collection.controller.CollectionRunResourceController.class,
      SearchQueryController.class,
      com.kholodilin.repogrowth.search.controller.SearchResourceController.class,
      EventsResourceController.class,
      TopicVisibilityController.class,
      com.kholodilin.repogrowth.topics.controller.TopicsResourceController.class,
      PortfolioDashboardController.class,
      com.kholodilin.repogrowth.traffic.controller.TrafficController.class,
      com.kholodilin.repogrowth.stats.controller.StatsController.class,
      SystemStatusController.class,
      SpaController.class
    })
@Import(WebMvcTestSecurityConfig.class)
class ApiControllersWebMvcTest {

  @Autowired MockMvc mockMvc;
  @Autowired CollectionController collectionController;
  @MockitoBean CollectionRunMapper collectionRunMapper;
  @MockitoBean CollectionRunQueryService collectionRunQueryService;
  @Autowired SearchQueryController searchQueryController;
  @Autowired TopicVisibilityController topicVisibilityController;

  private final CollectionRequestService collectionRequestService =
      mock(CollectionRequestService.class);

  @MockitoBean RepositoryService repositoryService;
  @MockitoBean RepositoryHealthService repositoryHealthService;
  @MockitoBean ActivityClassifier activityClassifier;
  @MockitoBean GitHubAccessStatusService accessStatusService;
  @MockitoBean PlanningWindow planningWindow;
  @MockitoBean CollectionRunJdbcRepository runRepository;
  @MockitoBean CollectionJobJdbcRepository jobRepository;
  @MockitoBean SearchQueryService searchQueryService;
  @MockitoBean SearchKpiService searchKpiService;
  @MockitoBean TopicsKpiService topicsKpiService;
  @MockitoBean CompetitorCardService competitorCardService;
  @MockitoBean GrowthEventService growthEventService;
  @MockitoBean ResourceAccessPolicy resourceAccessPolicy;
  @MockitoBean TopicVisibilityService topicVisibilityService;
  @MockitoBean TopicCompetitorService topicCompetitorService;
  @MockitoBean PortfolioDashboardService analyticsService;
  @MockitoBean AdminTrafficService adminTrafficService;
  @MockitoBean AdminStatsHistoryService adminStatsHistoryService;
  @MockitoBean com.kholodilin.repogrowth.traffic.service.TrafficReadService trafficReadService;
  @MockitoBean com.kholodilin.repogrowth.stats.service.StatsReadService statsReadService;
  @MockitoBean CurrentUserAccess currentUserAccess;
  @MockitoBean PublicationService publicationService;

  private Repository repository;
  private SearchQuery query;
  private TopicWatch watch;

  @BeforeEach
  void stubs() {
    ReflectionTestUtils.setField(collectionController, "commands", collectionRequestService);
    when(accessStatusService.status())
        .thenReturn(
            new GitHubAccessStatusService.Status(
                true, GitHubAccessStatusService.TokenState.UNVERIFIED));
    Instant now = Instant.parse("2026-09-23T06:00:00Z");
    repository =
        new Repository(
            13L,
            99L,
            1L,
            "outbox",
            "acme/outbox",
            "desc",
            "PUBLIC",
            "main",
            "Java",
            false,
            false,
            21,
            0,
            16,
            1,
            7,
            true,
            now,
            now,
            now,
            now,
            null,
            null,
            now,
            now);
    query = new SearchQuery(5L, 13L, "outbox", "outbox language:java", true, 50, now, now);
    watch = new TopicWatch(7L, 13L, "outbox", null, "stars", "desc", true, 50, now, now);
    GitHubOwner owner =
        new GitHubOwner(1L, 1L, "acme", OwnerType.USER, null, "https://github.com/acme", now, now);
    when(currentUserAccess.canSeePrivateRepositories()).thenReturn(true);
    when(repositoryService.list(false)).thenReturn(List.of(repository));
    when(repositoryService.list(true)).thenReturn(List.of(repository));
    when(repositoryService.get(13L)).thenReturn(repository);
    when(repositoryService.setTracking(13L, true)).thenReturn(repository);
    when(repositoryService.owner(1L)).thenReturn(owner);
    when(repositoryService.topics(13L)).thenReturn(List.of("kafka"));
    when(repositoryHealthService.forRepository(any()))
        .thenReturn(
            new RepositoryHealthResponse(
                List.of(new HealthCheckItem("Description", true)), List.of()));
    when(activityClassifier.classify(anyBoolean(), any())).thenReturn(ActivityStatus.ACTIVE);
    when(planningWindow.businessDate()).thenReturn(LocalDate.of(2026, 9, 23));
    when(planningWindow.zoneId()).thenReturn(ZoneId.of("Europe/Berlin"));
    when(planningWindow.from()).thenReturn(LocalTime.of(9, 30));
    when(planningWindow.to()).thenReturn(LocalTime.of(17, 0));
    when(planningWindow.isOpen()).thenReturn(true);
    CollectionRequestService.Created created =
        new CollectionRequestService.Created(UUID.randomUUID(), List.of(UUID.randomUUID()), false);
    CollectionRun run =
        new CollectionRun(
            1L, 13L, LocalDate.of(2026, 9, 23), CollectionRunStatus.SUCCESS, 1, 1, 0, now, now);
    when(collectionRequestService.manual(13L, LocalDate.of(2026, 9, 23))).thenReturn(created);
    when(collectionRequestService.searchCommand(5L, LocalDate.of(2026, 9, 23))).thenReturn(created);
    when(collectionRequestService.searchAllCommand(13L, LocalDate.of(2026, 9, 23)))
        .thenReturn(created);
    when(collectionRequestService.topicCommand(7L, LocalDate.of(2026, 9, 23))).thenReturn(created);
    when(collectionRequestService.topicsCommand(13L, "all", LocalDate.of(2026, 9, 23)))
        .thenReturn(created);
    when(runRepository.findById(1L)).thenReturn(Optional.of(run));
    when(jobRepository.findByRun(1L))
        .thenReturn(
            List.of(
                new CollectionJob(
                    2L,
                    1L,
                    13L,
                    CollectionJobType.TRAFFIC,
                    LocalDate.of(2026, 9, 23),
                    CollectionJobStatus.SUCCESS,
                    1,
                    null,
                    null,
                    null,
                    now,
                    now,
                    null,
                    null)));
    CollectionRunMapper.CollectionRunView runView =
        new CollectionRunMapper.CollectionRunView(
            1L,
            13L,
            "2026-09-23",
            "SUCCESS",
            1,
            1,
            0,
            now,
            now,
            List.of(
                new CollectionRunMapper.CollectionJobView(
                    2L, "TRAFFIC", "SUCCESS", 1, null, null, now, now)));
    when(collectionRunMapper.toView(any(CollectionRun.class))).thenReturn(runView);
    when(collectionRunQueryService.get(1L))
        .thenReturn(
            new CollectionRunMapper.CollectionRunResponse(
                1L,
                13L,
                "2026-09-23",
                "SUCCESS",
                1,
                1,
                0,
                Instant.parse("2026-09-23T06:00:00Z"),
                Instant.parse("2026-09-23T06:00:00Z"),
                List.of(
                    new CollectionRunMapper.CollectionJobResponse(
                        2L,
                        "TRAFFIC",
                        "SUCCESS",
                        1,
                        null,
                        null,
                        Instant.parse("2026-09-23T06:00:00Z"),
                        Instant.parse("2026-09-23T06:00:00Z")))));
    when(collectionRunQueryService.get(99L))
        .thenThrow(ApiException.notFound("Collection run not found"));
    when(collectionRunMapper.toResponse(any(CollectionRunMapper.CollectionRunView.class)))
        .thenReturn(
            new CollectionRunMapper.CollectionRunResponse(
                1L,
                13L,
                "2026-09-23",
                "SUCCESS",
                1,
                1,
                0,
                now,
                now,
                List.of(
                    new CollectionRunMapper.CollectionJobResponse(
                        2L, "TRAFFIC", "SUCCESS", 1, null, null, now, now))));
    when(searchQueryService.list(13L)).thenReturn(List.of(query));
    when(searchKpiService.repositoryQueriesWithHistory(eq(13L), anyBoolean(), any(Integer.class)))
        .thenReturn(List.of());
    when(topicVisibilityService.getWatch(7L)).thenReturn(watch);
    when(searchQueryService.create(eq(13L), any(), any(), any(), any())).thenReturn(query);
    when(searchQueryService.update(eq(5L), any(), any(), any(), any())).thenReturn(query);
    when(searchQueryService.getQuery(5L)).thenReturn(query);
    SearchRun searchRun =
        new SearchRun(
            9L,
            5L,
            13L,
            LocalDate.of(2026, 9, 23),
            SearchRunStatus.SUCCESS,
            1,
            null,
            null,
            null,
            now,
            now,
            now,
            80,
            4,
            "SUCCESS",
            "best_match",
            null,
            null);
    SearchQueryService.SearchRunResults results =
        new SearchQueryService.SearchRunResults(searchRun, query, List.of());
    when(searchQueryService.history(5L))
        .thenReturn(
            new SearchQueryService.SearchHistory(
                query, 4, null, 1, 2, 2, "stars", List.of(), now, "SUCCESS", "SUCCESS", 80,
                List.of()));
    when(searchQueryService.visibility(13L)).thenReturn(List.of());
    when(searchQueryService.latestResults(5L)).thenReturn(results);
    when(searchQueryService.results(9L)).thenReturn(results);
    when(searchQueryService.series(5L)).thenReturn(new SearchQueryService.SearchSeries(List.of()));
    when(competitorCardService.card(5L, 1L))
        .thenReturn(
            new CompetitorCardService.CompetitorCard(
                1L,
                "other/repo",
                "A neighbor worth reading",
                "https://github.com/other/repo",
                List.of("kafka"),
                List.of(new HealthCheckItem("Repository description exists", true)),
                List.of(
                    new CompetitorCardService.CompetitorPoint(LocalDate.of(2026, 9, 23), 10, 2))));
    GrowthEvent event =
        new GrowthEvent(
            1L,
            13L,
            now,
            "PROMOTION",
            "LINKEDIN_POST",
            "Posted",
            null,
            null,
            "MANUAL",
            null,
            now,
            now);
    when(growthEventService.list(eq(13L), anyString())).thenReturn(List.of(event));
    when(growthEventService.createManual(eq(13L), any(), any(), any(), any(), any()))
        .thenReturn(event);
    when(growthEventService.updateManual(eq(1L), any(), any(), any(), any(), any()))
        .thenReturn(event);
    when(growthEventService.settings(13L))
        .thenReturn(List.of(new GrowthEventSetting(13L, "STAR_MILESTONE", true, now, now)));
    when(growthEventService.updateSettings(eq(13L), any()))
        .thenReturn(List.of(new GrowthEventSetting(13L, "STAR_MILESTONE", false, now, now)));
    when(growthEventService.get(1L)).thenReturn(event);
    doNothing()
        .when(resourceAccessPolicy)
        .requireReadable(anyLong(), any(PublicationService.Module.class));
    doNothing()
        .when(resourceAccessPolicy)
        .requireAction(
            anyLong(),
            any(ResourceAccessPolicy.ResourceAction.class),
            any(PublicationService.Module.class));
    when(resourceAccessPolicy.filterReadable(any(), any()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(trafficReadService.ownerKpi(anyLong(), any(), anyString())).thenReturn(null);
    when(trafficReadService.repositoryKpi(anyLong(), anyString())).thenReturn(null);
    when(trafficReadService.ownerDailyHistory(anyLong(), any(), anyString())).thenReturn(null);
    when(trafficReadService.dailyHistory(anyLong(), anyString())).thenReturn(null);
    when(trafficReadService.latestReferrers(anyLong())).thenReturn(null);
    when(trafficReadService.latestPaths(anyLong(), anyBoolean())).thenReturn(null);
    when(trafficReadService.referrerHistory(anyLong(), any(), any())).thenReturn(null);
    when(trafficReadService.pathHistory(anyLong(), any(), any(), anyBoolean())).thenReturn(null);
    when(statsReadService.ownerKpi(anyLong(), any(), anyString())).thenReturn(null);
    when(statsReadService.repositoryKpi(anyLong(), anyString())).thenReturn(null);
    when(statsReadService.ownerHistoryResponse(anyLong(), any(), anyString())).thenReturn(null);
    when(searchKpiService.ownerKpi(anyLong(), any(), anyString())).thenReturn(null);
    when(searchKpiService.repositoryKpi(anyLong(), anyString())).thenReturn(null);
    when(topicsKpiService.ownerKpi(anyLong(), any(), anyString(), anyString())).thenReturn(null);
    when(topicsKpiService.repositoryKpi(anyLong(), anyString(), anyString())).thenReturn(null);
    TopicRun topicRun =
        new TopicRun(
            3L,
            7L,
            13L,
            LocalDate.of(2026, 9, 23),
            SearchRunStatus.SUCCESS,
            1,
            null,
            null,
            null,
            now,
            now,
            now,
            170,
            24,
            null,
            null);
    TopicVisibilityService.TopicHistory history =
        new TopicVisibilityService.TopicHistory(
            watch, 24, null, null, null, 24, List.of(), now, "SUCCESS", 170, List.of());
    TopicVisibilityService.TopicRunResults topicResults =
        new TopicVisibilityService.TopicRunResults(topicRun, watch, List.of());
    when(topicVisibilityService.visibility(eq(13L), anyString())).thenReturn(List.of(history));
    when(topicVisibilityService.history(7L)).thenReturn(history);
    when(topicVisibilityService.latestResults(7L)).thenReturn(topicResults);
    when(topicVisibilityService.historyByTopic(eq(13L), eq("outbox"), anyString()))
        .thenReturn(history);
    when(topicVisibilityService.latestResultsByTopic(eq(13L), eq("outbox"), anyString()))
        .thenReturn(topicResults);
    when(topicVisibilityService.results(3L)).thenReturn(topicResults);
    when(topicCompetitorService.card(eq(13L), eq("outbox"), eq("all"), eq(2L)))
        .thenReturn(
            new CompetitorCardService.CompetitorCard(
                2L,
                "big/outbox",
                "A neighbor worth reading",
                "https://github.com/big/outbox",
                List.of("kafka"),
                List.of(new HealthCheckItem("Repository description exists", true)),
                List.of(
                    new CompetitorCardService.CompetitorPoint(LocalDate.of(2026, 9, 23), 10, 2))));
    when(analyticsService.dashboard(anyString(), any(), anyBoolean())).thenReturn(null);
    when(analyticsService.portfolio(anyString(), anyBoolean())).thenReturn(null);
    when(adminTrafficService.traffic(eq(13L), anyString(), anyBoolean())).thenReturn(null);
    when(adminTrafficService.snapshotHistory(eq(13L), any(), any(), any(), anyBoolean()))
        .thenReturn(null);
    when(adminStatsHistoryService.statsHistory(eq(13L), anyString())).thenReturn(null);
  }

  @Test
  void repositoryCollectionSearchEventAndTopicEndpoints() throws Exception {
    mockMvc
        .perform(get("/api/v1/admin/repositories"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].fullName").value("acme/outbox"));
    mockMvc.perform(get("/api/v1/admin/repositories/13")).andExpect(status().isOk());
    mockMvc
        .perform(
            post("/api/v1/admin/repositories/13/tracking")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true}"))
        .andExpect(status().isOk());
    mockMvc
        .perform(post("/api/v1/admin/repositories/13/collect"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.coalesced").value(false));
    verify(collectionRequestService).manual(13L, LocalDate.of(2026, 9, 23));
    mockMvc
        .perform(get("/api/v1/collection-runs/1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.jobs[0].jobType").value("TRAFFIC"));
    mockMvc.perform(get("/api/v1/repositories/13/search-queries")).andExpect(status().isOk());
    mockMvc
        .perform(
            post("/api/v1/repositories/13/search-queries")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"q\",\"query\":\"outbox\",\"enabled\":true,\"resultLimit\":50}"))
        .andExpect(status().isCreated());
    mockMvc
        .perform(
            put("/api/v1/search-queries/5")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"name\":\"q\",\"query\":\"outbox\",\"enabled\":true,\"resultLimit\":50}"))
        .andExpect(status().isOk());
    mockMvc.perform(delete("/api/v1/search-queries/5")).andExpect(status().isOk());
    mockMvc.perform(get("/api/v1/search-queries/5")).andExpect(status().isOk());
    mockMvc.perform(get("/api/v1/search-queries/5/history")).andExpect(status().isOk());
    mockMvc.perform(get("/api/v1/repositories/13/search-visibility")).andExpect(status().isOk());
    mockMvc.perform(get("/api/v1/search-queries/5/results")).andExpect(status().isOk());
    mockMvc
        .perform(get("/api/v1/search-queries/5/series"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.series").isEmpty());
    mockMvc
        .perform(get("/api/v1/search-queries/5/competitors/1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.fullName").value("other/repo"))
        .andExpect(jsonPath("$.discoverability[0].label").value("Repository description exists"))
        .andExpect(jsonPath("$.points[0].stars").value(10));
    mockMvc.perform(get("/api/v1/search-runs/9/results")).andExpect(status().isOk());
    mockMvc.perform(get("/api/v1/repositories/13/growth-events")).andExpect(status().isOk());
    mockMvc
        .perform(
            post("/api/v1/repositories/13/growth-events")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"type\":\"CUSTOM\",\"eventAt\":\"2026-09-22T12:00:00Z\",\"title\":\"Posted\"}"))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            put("/api/v1/growth-events/1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"type\":\"CUSTOM\",\"eventAt\":\"2026-09-22T12:00:00Z\",\"title\":\"Updated\"}"))
        .andExpect(status().isOk());
    mockMvc.perform(delete("/api/v1/growth-events/1")).andExpect(status().isOk());
    mockMvc
        .perform(get("/api/v1/repositories/13/growth-event-settings"))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            put("/api/v1/repositories/13/growth-event-settings")
                .contentType(MediaType.APPLICATION_JSON)
                .content("[{\"eventType\":\"STAR_MILESTONE\",\"enabled\":false}]"))
        .andExpect(status().isOk());
    mockMvc.perform(get("/api/v1/repositories/13/topics-visibility")).andExpect(status().isOk());
    mockMvc.perform(get("/api/v1/topic-watches/7/history")).andExpect(status().isOk());
    mockMvc.perform(get("/api/v1/topic-watches/7/results")).andExpect(status().isOk());
    mockMvc
        .perform(get("/api/v1/repositories/13/topics/outbox/history"))
        .andExpect(status().isOk());
    mockMvc
        .perform(get("/api/v1/repositories/13/topics/outbox/results"))
        .andExpect(status().isOk());
    when(topicCompetitorService.series(13L, "outbox", "language"))
        .thenReturn(
            List.of(
                new TopicCompetitorService.RankSeries(
                    2L,
                    "big/outbox",
                    List.of(new TopicCompetitorService.RankPoint(LocalDate.of(2026, 9, 23), 1)))));
    mockMvc
        .perform(get("/api/v1/repositories/13/topics/outbox/series?scope=language"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].points[0].position").value(1));
    mockMvc
        .perform(get("/api/v1/repositories/13/topics/outbox/competitors/2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.fullName").value("big/outbox"))
        .andExpect(jsonPath("$.points[0].stars").value(10));
    mockMvc.perform(get("/api/v1/topic-runs/3/results")).andExpect(status().isOk());
    mockMvc.perform(get("/api/v1/dashboard")).andExpect(status().isOk());
    mockMvc.perform(get("/api/v1/portfolio")).andExpect(status().isOk());
    mockMvc.perform(get("/api/v1/repositories/13/traffic")).andExpect(status().isOk());
    mockMvc
        .perform(get("/api/v1/repositories/13/traffic/snapshot-history"))
        .andExpect(status().isOk());
    mockMvc
        .perform(
            get(
                "/api/v1/repositories/13/traffic/snapshot-history?kind=paths&period=all&days=14&includeServicePaths=true"))
        .andExpect(status().isOk());
    verify(adminTrafficService).snapshotHistory(13L, "paths", "all", 14, true);
    mockMvc.perform(get("/api/v1/repositories/13/stats-history")).andExpect(status().isOk());
    mockMvc.perform(get("/api/v1/admin/system/status")).andExpect(status().isOk());
    mockMvc.perform(get("/dashboard")).andExpect(status().isNotFound());
  }

  @Test
  void mapsApiAndGitHubErrors() throws Exception {
    when(repositoryService.get(8L)).thenThrow(ApiException.notFound("missing"));
    mockMvc
        .perform(get("/api/v1/admin/repositories/8"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));

    when(runRepository.findById(99L)).thenReturn(Optional.empty());
    mockMvc.perform(get("/api/v1/collection-runs/99")).andExpect(status().isNotFound());

    doThrow(GitHubException.auth(401, "bad token")).when(repositoryService).list(false);
    mockMvc
        .perform(get("/api/v1/admin/repositories?refresh=false"))
        .andExpect(status().isUnauthorized());
    doThrow(GitHubException.rateLimit(Instant.parse("2026-09-23T07:00:00Z"), "slow"))
        .when(repositoryService)
        .list(false);
    mockMvc
        .perform(get("/api/v1/admin/repositories?refresh=false"))
        .andExpect(status().isTooManyRequests());
    doThrow(GitHubException.notFound("gone")).when(repositoryService).list(false);
    mockMvc
        .perform(get("/api/v1/admin/repositories?refresh=false"))
        .andExpect(status().isNotFound());
    doThrow(GitHubException.validation("bad")).when(repositoryService).list(false);
    mockMvc
        .perform(get("/api/v1/admin/repositories?refresh=false"))
        .andExpect(status().isBadRequest());
    doThrow(GitHubException.api(502, true, null, "down")).when(repositoryService).list(false);
    mockMvc
        .perform(get("/api/v1/admin/repositories?refresh=false"))
        .andExpect(status().isBadGateway());

    mockMvc
        .perform(
            post("/api/v1/admin/repositories/13/tracking")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            post("/api/v1/admin/repositories/13/tracking")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{"))
        .andExpect(status().isBadRequest());
    doThrow(new IllegalStateException("boom")).when(repositoryService).get(13L);
    mockMvc
        .perform(get("/api/v1/admin/repositories/13"))
        .andExpect(status().isInternalServerError());
  }

  @Test
  void statusReportsCredentialStateWithoutClaimingTheTokenWorks() throws Exception {
    when(accessStatusService.status())
        .thenReturn(
            new GitHubAccessStatusService.Status(
                false, GitHubAccessStatusService.TokenState.MISSING));
    mockMvc
        .perform(get("/api/v1/admin/system/status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.credentialsConfigured").value(false))
        .andExpect(jsonPath("$.tokenState").value("MISSING"))
        .andExpect(jsonPath("$.planner.timezone").value("Europe/Berlin"))
        .andExpect(jsonPath("$.planner.from").value("09:30"))
        .andExpect(jsonPath("$.planner.to").value("17:00"))
        .andExpect(jsonPath("$.planner.open").value(true));

    when(accessStatusService.status())
        .thenReturn(
            new GitHubAccessStatusService.Status(
                true, GitHubAccessStatusService.TokenState.UNVERIFIED));
    mockMvc
        .perform(get("/api/v1/admin/system/status"))
        .andExpect(jsonPath("$.credentialsConfigured").value(true))
        .andExpect(jsonPath("$.tokenState").value("UNVERIFIED"))
        .andExpect(jsonPath("$.githubTokenMasked").doesNotExist());

    when(accessStatusService.status())
        .thenReturn(
            new GitHubAccessStatusService.Status(
                true, GitHubAccessStatusService.TokenState.ORCHESTRATION_DISABLED));
    mockMvc
        .perform(get("/api/v1/admin/system/status"))
        .andExpect(jsonPath("$.tokenState").value("ORCHESTRATION_DISABLED"));

    when(accessStatusService.status())
        .thenReturn(
            new GitHubAccessStatusService.Status(
                true, GitHubAccessStatusService.TokenState.COLLECTOR_UNAVAILABLE));
    mockMvc
        .perform(get("/api/v1/admin/system/status"))
        .andExpect(jsonPath("$.tokenState").value("COLLECTOR_UNAVAILABLE"));
  }

  @Test
  void collectIsUnavailableWithoutCollectorOrchestration() throws Exception {
    ReflectionTestUtils.setField(collectionController, "commands", null);
    try {
      mockMvc
          .perform(post("/api/v1/admin/repositories/13/collect"))
          .andExpect(status().isServiceUnavailable());
    } finally {
      ReflectionTestUtils.setField(collectionController, "commands", collectionRequestService);
    }
  }

  @Test
  void resourceTrafficStatsSearchTopicsAndOwnerEventsContracts() throws Exception {
    mockMvc.perform(get("/api/v1/owners/1/traffic/kpi")).andExpect(status().isOk());
    mockMvc.perform(get("/api/v1/repositories/13/traffic/kpi")).andExpect(status().isOk());
    mockMvc.perform(get("/api/v1/owners/1/traffic-history")).andExpect(status().isOk());
    mockMvc.perform(get("/api/v1/repositories/13/traffic-history")).andExpect(status().isOk());
    mockMvc.perform(get("/api/v1/repositories/13/referrers")).andExpect(status().isOk());
    mockMvc
        .perform(get("/api/v1/repositories/13/paths?includeServicePaths=true"))
        .andExpect(status().isOk());
    mockMvc
        .perform(get("/api/v1/repositories/13/referrer-history?period=7d"))
        .andExpect(status().isOk());
    mockMvc
        .perform(get("/api/v1/repositories/13/path-history?period=7d"))
        .andExpect(status().isOk());
    verify(trafficReadService).pathHistory(13L, "7d", null, false);

    mockMvc.perform(get("/api/v1/owners/1/stats/kpi")).andExpect(status().isOk());
    mockMvc.perform(get("/api/v1/repositories/13/stats/kpi")).andExpect(status().isOk());
    mockMvc.perform(get("/api/v1/owners/1/stats-history")).andExpect(status().isOk());
    mockMvc.perform(get("/api/v1/repositories/13/stats-history")).andExpect(status().isOk());
    verify(adminStatsHistoryService).statsHistory(13L, "7d");

    mockMvc.perform(get("/api/v1/owners/1/search/kpi")).andExpect(status().isOk());
    mockMvc.perform(get("/api/v1/repositories/13/search/kpi")).andExpect(status().isOk());
    mockMvc.perform(get("/api/v1/owners/1/topics/kpi")).andExpect(status().isOk());
    mockMvc
        .perform(get("/api/v1/repositories/13/topics/kpi?scope=language"))
        .andExpect(status().isOk());
    mockMvc
        .perform(get("/api/v1/owners/1/growth-events"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].title").value("Posted"));
  }

  @Test
  void ownerResourceReadsReturnNotFoundWhenNoReadableRepositories() throws Exception {
    when(resourceAccessPolicy.filterReadable(any(), any())).thenReturn(List.of());
    mockMvc
        .perform(get("/api/v1/owners/1/traffic/kpi"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"))
        .andExpect(jsonPath("$.message").value("Owner not found"));
    mockMvc
        .perform(get("/api/v1/owners/1/stats/kpi"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    mockMvc
        .perform(get("/api/v1/owners/1/search/kpi"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    mockMvc
        .perform(get("/api/v1/owners/1/topics/kpi"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    mockMvc
        .perform(get("/api/v1/owners/1/growth-events"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  @Test
  void growthEventSettingsStayPrivateToOperators() throws Exception {
    when(currentUserAccess.canSeePrivateRepositories()).thenReturn(false);
    when(currentUserAccess.authenticated()).thenReturn(false);
    mockMvc
        .perform(get("/api/v1/repositories/13/growth-event-settings"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    when(currentUserAccess.authenticated()).thenReturn(true);
    mockMvc
        .perform(get("/api/v1/repositories/13/growth-event-settings"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  void repositorySignalsAreExposedOnTheDetail() throws Exception {
    when(repositoryService.signalsFor(any()))
        .thenReturn(Map.of(13L, new RepositorySignals(false, true)));
    mockMvc
        .perform(get("/api/v1/admin/repositories/13"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessible").value(false))
        .andExpect(jsonPath("$.trafficPermissionMissing").value(true));
    when(repositoryService.signalsFor(any()))
        .thenReturn(Map.of(13L, new RepositorySignals(true, false)));
    mockMvc
        .perform(get("/api/v1/admin/repositories/13"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessible").value(true))
        .andExpect(jsonPath("$.trafficPermissionMissing").value(false));
  }
}
