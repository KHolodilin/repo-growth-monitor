package com.kholodilin.repogrowth.collection.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.kholodilin.collector.contracts.CollectorHttpHeaders;
import com.kholodilin.repogrowth.administration.service.PublicationService;
import com.kholodilin.repogrowth.collection.planner.PlanningWindow;
import com.kholodilin.repogrowth.collection.service.CollectionRequestService;
import com.kholodilin.repogrowth.collection.service.IdempotentCollectionRequests;
import com.kholodilin.repogrowth.common.api.ApiException;
import com.kholodilin.repogrowth.common.api.GlobalExceptionHandler;
import com.kholodilin.repogrowth.identity.security.AppUserPrincipal;
import com.kholodilin.repogrowth.identity.security.ResourceAccessPolicy;
import com.kholodilin.repogrowth.search.model.SearchQuery;
import com.kholodilin.repogrowth.search.service.SearchQueryService;
import com.kholodilin.repogrowth.topics.model.TopicWatch;
import com.kholodilin.repogrowth.topics.service.TopicVisibilityService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

class CollectionResourceControllerTest {

  private ResourceAccessPolicy access;
  private IdempotentCollectionRequests idempotent;
  private PlanningWindow window;
  private SearchQueryService searchQueries;
  private TopicVisibilityService topicWatches;
  private CollectionRequestService commands;
  private CollectionResourceController controller;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    access = mock(ResourceAccessPolicy.class);
    idempotent = mock(IdempotentCollectionRequests.class);
    window = mock(PlanningWindow.class);
    searchQueries = mock(SearchQueryService.class);
    topicWatches = mock(TopicVisibilityService.class);
    commands = mock(CollectionRequestService.class);
    controller =
        new CollectionResourceController(access, idempotent, window, searchQueries, topicWatches);
    ReflectionTestUtils.setField(controller, "commands", commands);
    when(window.businessDate()).thenReturn(LocalDate.of(2026, 11, 10));
    mvc = standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
  }

  @Test
  void repositoryCollectionRequiresIdempotencyKeyAndAdmin() throws Exception {
    doNothing()
        .when(access)
        .requireAction(
            eq(13L),
            eq(ResourceAccessPolicy.ResourceAction.MUTATE),
            eq(PublicationService.Module.DASHBOARD));
    UUID requestId = UUID.randomUUID();
    when(idempotent.create(anyLong(), any(), anyLong(), any()))
        .thenReturn(
            new CollectionRequestService.Created(requestId, List.of(UUID.randomUUID()), false));
    AppUserPrincipal principal = new AppUserPrincipal(42L, "admin");
    var auth = new UsernamePasswordAuthenticationToken(principal, null, List.of());

    mvc.perform(
            post("/api/v1/repositories/13/collections")
                .principal(auth)
                .header(CollectorHttpHeaders.IDEMPOTENCY_KEY, "key-1")
                .contentType(MediaType.APPLICATION_JSON))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.coalesced").value(false));
  }

  @Test
  void runCommandsReturnCreatedPayloadAndUnavailableWithoutOrchestration() throws Exception {
    Instant now = Instant.parse("2026-11-10T06:00:00Z");
    SearchQuery query = new SearchQuery(5L, 13L, "outbox", "q", true, 50, now, now);
    TopicWatch watch = new TopicWatch(7L, 13L, "outbox", null, "stars", "desc", true, 50, now, now);
    doNothing()
        .when(access)
        .requireAction(
            eq(13L),
            eq(ResourceAccessPolicy.ResourceAction.MUTATE),
            eq(PublicationService.Module.DASHBOARD));
    doNothing()
        .when(access)
        .requireAction(
            eq(13L),
            eq(ResourceAccessPolicy.ResourceAction.MUTATE),
            eq(PublicationService.Module.TOPICS));
    when(searchQueries.getQuery(5L)).thenReturn(query);
    when(topicWatches.getWatch(7L)).thenReturn(watch);
    var created =
        new CollectionRequestService.Created(UUID.randomUUID(), List.of(UUID.randomUUID()), false);
    when(commands.searchCommand(anyLong(), any())).thenReturn(created);
    when(commands.searchAllCommand(anyLong(), any())).thenReturn(created);
    when(commands.topicCommand(anyLong(), any())).thenReturn(created);
    when(commands.topicsCommand(anyLong(), any(), any())).thenReturn(created);

    mvc.perform(post("/api/v1/search-queries/5/run"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.coalesced").value(false));
    mvc.perform(post("/api/v1/repositories/13/search-queries/run"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.coalesced").value(false));
    mvc.perform(post("/api/v1/topic-watches/7/run"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.coalesced").value(false));
    mvc.perform(post("/api/v1/repositories/13/topic-watches/run").param("scope", "language"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.coalesced").value(false));

    when(topicWatches.getWatch(9L)).thenThrow(ApiException.notFound("Topic watch not found"));
    mvc.perform(post("/api/v1/topic-watches/9/run"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));

    ReflectionTestUtils.setField(controller, "commands", null);
    mvc.perform(post("/api/v1/search-queries/5/run"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
  }
}
