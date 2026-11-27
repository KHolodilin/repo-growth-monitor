package com.kholodilin.repogrowth.collection.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

import com.kholodilin.repogrowth.administration.service.PublicationService;
import com.kholodilin.repogrowth.collection.planner.PlanningWindow;
import com.kholodilin.repogrowth.collection.service.CollectionRequestService;
import com.kholodilin.repogrowth.collection.service.IdempotentCollectionRequests;
import com.kholodilin.repogrowth.common.api.ApiException;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class CollectionResourceControllerTest {

  @Mock ResourceAccessPolicy access;
  @Mock IdempotentCollectionRequests idempotent;
  @Mock PlanningWindow window;
  @Mock SearchQueryService searchQueries;
  @Mock TopicVisibilityService topicWatches;
  @Mock CollectionRequestService commands;

  CollectionResourceController controller;

  @BeforeEach
  void setUp() {
    controller =
        new CollectionResourceController(access, idempotent, window, searchQueries, topicWatches);
    ReflectionTestUtils.setField(controller, "commands", commands);
    when(window.businessDate()).thenReturn(LocalDate.of(2026, 11, 10));
  }

  @Test
  void repositoryCollectionRequiresIdempotencyKeyAndAdmin() {
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
    assertThat(controller.collect(13L, "key-1", auth).getStatusCode().value()).isEqualTo(201);
  }

  @Test
  void runCommandsDelegateToOrchestration() {
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

    assertThat(controller.runSearchQuery(5L).getBody()).isEqualTo(created);
    assertThat(controller.runSearchQuery(5L).getStatusCode().value()).isEqualTo(201);
    assertThat(controller.runRepositorySearchQueries(13L).getBody()).isEqualTo(created);
    assertThat(controller.runTopicWatch(7L).getBody()).isEqualTo(created);
    assertThat(controller.runRepositoryTopicWatches(13L, "language").getBody()).isEqualTo(created);
    when(topicWatches.getWatch(9L)).thenThrow(ApiException.notFound("Topic watch not found"));
    assertThatThrownBy(() -> controller.runTopicWatch(9L)).isInstanceOf(ApiException.class);

    ReflectionTestUtils.setField(controller, "commands", null);
    assertThatThrownBy(() -> controller.runSearchQuery(5L)).isInstanceOf(ApiException.class);
  }
}
