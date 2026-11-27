package com.kholodilin.repogrowth.topics.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

import com.kholodilin.repogrowth.administration.service.PublicationService;
import com.kholodilin.repogrowth.catalog.model.GitHubOwner;
import com.kholodilin.repogrowth.catalog.model.OwnerType;
import com.kholodilin.repogrowth.catalog.model.Repository;
import com.kholodilin.repogrowth.catalog.service.RepositoryService;
import com.kholodilin.repogrowth.common.api.ApiException;
import com.kholodilin.repogrowth.identity.security.ResourceAccessPolicy;
import com.kholodilin.repogrowth.topics.model.TopicWatch;
import com.kholodilin.repogrowth.topics.service.TopicCompetitorService;
import com.kholodilin.repogrowth.topics.service.TopicVisibilityService;
import com.kholodilin.repogrowth.topics.service.TopicVisibilityService.TopicHistory;
import com.kholodilin.repogrowth.topics.service.TopicVisibilityService.TopicRunResults;
import com.kholodilin.repogrowth.topics.service.TopicsKpiService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TopicsResourceControllerTest {

  @Mock ResourceAccessPolicy access;
  @Mock RepositoryService repositories;
  @Mock TopicsKpiService kpi;
  @Mock TopicVisibilityService topics;
  @Mock TopicCompetitorService topicCompetitors;

  TopicsResourceController controller;
  TopicWatch watch;
  Repository repository;

  @BeforeEach
  void setUp() {
    controller =
        new TopicsResourceController(access, repositories, kpi, topics, topicCompetitors);
    Instant now = Instant.parse("2026-11-10T06:00:00Z");
    watch = new TopicWatch(7L, 13L, "outbox", null, "stars", "desc", true, 50, now, now);
    repository =
        new Repository(
            13L,
            99L,
            1L,
            "outbox",
            "acme/outbox",
            "d",
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
  }

  @Test
  void delegatesKpiWatchesAndCompetitors() {
    when(repositories.owner(1L))
        .thenReturn(
            new GitHubOwner(
                1L,
                1L,
                "acme",
                OwnerType.USER,
                null,
                "https://github.com/acme",
                Instant.now(),
                Instant.now()));
    when(repositories.list(false)).thenReturn(List.of(repository));
    when(access.filterReadable(List.of(13L), PublicationService.Module.TOPICS))
        .thenReturn(List.of(13L));
    when(kpi.ownerKpi(eq(1L), eq(List.of(13L)), eq("7d"), eq("all"))).thenReturn(null);
    when(kpi.repositoryKpi(13L, "7d", "language")).thenReturn(null);
    doNothing().when(access).requireReadable(13L, PublicationService.Module.TOPICS);
    controller.ownerTopicsKpi(1L, "7d", "all");
    controller.repositoryTopicsKpi(13L, "7d", "language");
    when(kpi.repositoryWatchesWithHistory(13L, "all", true, 20)).thenReturn(List.of());
    assertThat(controller.listWatches(13L, "all", "history", 20)).isEmpty();

    when(topics.getWatch(7L)).thenReturn(watch);
    assertThat(controller.get(7L)).isEqualTo(watch);
    when(topics.history(7L))
        .thenReturn(
            new TopicHistory(
                watch, 5, null, null, null, 5, List.of(), Instant.now(), "SUCCESS", 1, List.of()));
    assertThat(controller.history(7L).watch()).isEqualTo(watch);
    when(topics.latestResults(7L)).thenReturn(new TopicRunResults(null, watch, List.of()));
    assertThat(controller.results(7L).watch()).isEqualTo(watch);
    when(topicCompetitors.series(anyLong(), anyString(), anyString())).thenReturn(List.of());
    assertThat(controller.series(7L)).isEmpty();
    when(topicCompetitors.card(anyLong(), anyString(), anyString(), anyLong())).thenReturn(null);
    controller.competitor(7L, 99L);
    when(topics.results(3L)).thenReturn(new TopicRunResults(null, watch, List.of()));
    assertThat(controller.runResults(3L).watch()).isEqualTo(watch);
  }

  @Test
  void ownerKpiRejectsWhenNoReadableRepositories() {
    when(repositories.owner(1L))
        .thenReturn(
            new GitHubOwner(
                1L,
                1L,
                "acme",
                OwnerType.USER,
                null,
                "https://github.com/acme",
                Instant.now(),
                Instant.now()));
    when(repositories.list(false)).thenReturn(List.of(repository));
    when(access.filterReadable(List.of(13L), PublicationService.Module.TOPICS))
        .thenReturn(List.of());
    assertThatThrownBy(() -> controller.ownerTopicsKpi(1L, "7d", "all"))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("Owner not found");
  }

  @Test
  void missingWatchIsNotFound() {
    when(topics.getWatch(9L)).thenThrow(ApiException.notFound("Topic watch not found"));
    assertThatThrownBy(() -> controller.get(9L)).isInstanceOf(ApiException.class);
  }
}
