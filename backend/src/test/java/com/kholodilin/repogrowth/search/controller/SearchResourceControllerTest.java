package com.kholodilin.repogrowth.search.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kholodilin.repogrowth.administration.service.PublicationService;
import com.kholodilin.repogrowth.catalog.model.GitHubOwner;
import com.kholodilin.repogrowth.catalog.model.OwnerType;
import com.kholodilin.repogrowth.catalog.model.Repository;
import com.kholodilin.repogrowth.catalog.service.RepositoryService;
import com.kholodilin.repogrowth.common.api.ApiException;
import com.kholodilin.repogrowth.identity.security.ResourceAccessPolicy;
import com.kholodilin.repogrowth.search.model.SearchQuery;
import com.kholodilin.repogrowth.search.service.CompetitorCardService;
import com.kholodilin.repogrowth.search.service.SearchKpiService;
import com.kholodilin.repogrowth.search.service.SearchQueryService;
import com.kholodilin.repogrowth.search.service.SearchQueryService.SearchHistory;
import com.kholodilin.repogrowth.search.service.SearchQueryService.SearchRunResults;
import com.kholodilin.repogrowth.search.service.SearchQueryService.SearchSeries;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SearchResourceControllerTest {

  @Mock ResourceAccessPolicy access;
  @Mock RepositoryService repositories;
  @Mock SearchKpiService kpi;
  @Mock SearchQueryService searchQueries;
  @Mock CompetitorCardService competitors;

  SearchResourceController controller;
  SearchQuery query;
  Repository repository;

  @BeforeEach
  void setUp() {
    controller =
        new SearchResourceController(access, repositories, kpi, searchQueries, competitors);
    Instant now = Instant.parse("2026-11-10T06:00:00Z");
    query = new SearchQuery(5L, 13L, "outbox", "outbox language:java", true, 50, now, now);
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
  void delegatesKpiAndQueryReads() {
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
    when(access.filterReadable(List.of(13L), PublicationService.Module.DASHBOARD))
        .thenReturn(List.of(13L));
    when(kpi.ownerKpi(eq(1L), eq(List.of(13L)), eq("7d"))).thenReturn(null);
    when(kpi.repositoryKpi(13L, "7d")).thenReturn(null);
    doNothing().when(access).requireReadable(13L, PublicationService.Module.DASHBOARD);
    controller.ownerSearchKpi(1L, "7d");
    controller.repositorySearchKpi(13L, "7d");
    when(kpi.repositoryQueriesWithHistory(anyLong(), anyBoolean(), anyInt())).thenReturn(List.of());
    assertThat(controller.listQueries(13L, "history", 20)).isEmpty();
    verify(kpi).repositoryQueriesWithHistory(13L, true, 20);

    when(searchQueries.getQuery(5L)).thenReturn(query);
    assertThat(controller.get(5L)).isEqualTo(query);
    when(searchQueries.history(5L))
        .thenReturn(
            new SearchHistory(
                query,
                2,
                null,
                null,
                null,
                2,
                "stars",
                List.of(),
                Instant.now(),
                "SUCCESS",
                null,
                1,
                List.of()));
    assertThat(controller.history(5L).currentRank()).isEqualTo(2);
    when(searchQueries.latestResults(5L)).thenReturn(new SearchRunResults(null, query, List.of()));
    assertThat(controller.results(5L).query()).isEqualTo(query);
    when(searchQueries.series(5L)).thenReturn(new SearchSeries(List.of()));
    assertThat(controller.series(5L).series()).isEmpty();
    when(competitors.card(5L, 99L)).thenReturn(null);
    controller.competitor(5L, 99L);
    when(searchQueries.results(7L)).thenReturn(new SearchRunResults(null, query, List.of()));
    assertThat(controller.runResults(7L).query()).isEqualTo(query);
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
    when(access.filterReadable(List.of(13L), PublicationService.Module.DASHBOARD))
        .thenReturn(List.of());
    assertThatThrownBy(() -> controller.ownerSearchKpi(1L, "7d"))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("Owner not found");
  }

  @Test
  void mutatesQueriesWithAccessChecks() {
    doNothing()
        .when(access)
        .requireAction(
            eq(13L),
            eq(ResourceAccessPolicy.ResourceAction.MUTATE),
            eq(PublicationService.Module.DASHBOARD));
    when(searchQueries.create(anyLong(), any(), any(), any(), any())).thenReturn(query);
    assertThat(
            controller.create(
                13L,
                new SearchQueryController.SearchQueryRequest(
                    "outbox", "outbox language:java", true, 50)))
        .isEqualTo(query);
    when(searchQueries.getQuery(5L)).thenReturn(query);
    when(searchQueries.update(anyLong(), any(), any(), any(), any())).thenReturn(query);
    assertThat(
            controller.update(
                5L,
                new SearchQueryController.SearchQueryRequest(
                    "outbox", "outbox language:java", true, 50)))
        .isEqualTo(query);
    controller.delete(5L);
    verify(searchQueries).delete(5L);
  }
}
