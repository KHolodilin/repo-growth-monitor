package com.kholodilin.repogrowth.search.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

import com.kholodilin.repogrowth.administration.service.PublicationService;
import com.kholodilin.repogrowth.catalog.model.GitHubOwner;
import com.kholodilin.repogrowth.catalog.model.OwnerType;
import com.kholodilin.repogrowth.catalog.model.Repository;
import com.kholodilin.repogrowth.catalog.service.RepositoryService;
import com.kholodilin.repogrowth.common.api.ApiException;
import com.kholodilin.repogrowth.common.model.kpi.KpiScope;
import com.kholodilin.repogrowth.identity.security.ResourceAccessPolicy;
import com.kholodilin.repogrowth.search.model.SearchQuery;
import com.kholodilin.repogrowth.search.service.SearchQueryService.RankPoint;
import com.kholodilin.repogrowth.search.service.SearchQueryService.SearchHistory;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SearchKpiServiceTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);
  private static final Clock CLOCK =
      Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

  @Mock ResourceAccessPolicy access;
  @Mock RepositoryService repositories;
  @Mock SearchQueryService searchQueries;

  SearchKpiService service;
  SearchQuery query;
  Repository repository;

  @BeforeEach
  void setUp() {
    service = new SearchKpiService(access, repositories, searchQueries, CLOCK);
    Instant now = Instant.parse("2026-11-01T00:00:00Z");
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
  void repositoryKpiBuildsBandMetricsAndUsage() {
    doNothing().when(access).requireReadable(13L, PublicationService.Module.DASHBOARD);
    when(searchQueries.visibility(13L))
        .thenReturn(
            List.of(
                history(
                    query,
                    2,
                    List.of(
                        point(TODAY.minusDays(2), 4),
                        point(TODAY.minusDays(1), 3),
                        point(TODAY, 2)))));
    var kpi = service.repositoryKpi(13L, "7d");
    assertThat(kpi.scope()).isEqualTo(KpiScope.REPOSITORY);
    assertThat(kpi.usage().used()).isEqualTo(1);
    assertThat(kpi.metrics().top3().value()).isNotNull();
  }

  @Test
  void ownerKpiAggregatesQueriesAndRejectsEmptyOwners() {
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
    when(searchQueries.visibility(13L))
        .thenReturn(List.of(history(query, 2, List.of(point(TODAY, 2)))));
    assertThat(service.ownerKpi(1L, "7d").scope()).isEqualTo(KpiScope.OWNER);

    when(access.filterReadable(List.of(13L), PublicationService.Module.DASHBOARD))
        .thenReturn(List.of());
    assertThatThrownBy(() -> service.ownerKpi(1L, "7d")).isInstanceOf(ApiException.class);
  }

  @Test
  void repositoryQueriesWithHistoryRespectsIncludeFlagAndCap() {
    doNothing().when(access).requireReadable(13L, PublicationService.Module.DASHBOARD);
    when(searchQueries.visibility(13L))
        .thenReturn(List.of(history(query, 2, List.of(point(TODAY, 2)))));
    assertThat(service.repositoryQueriesWithHistory(13L, true, 10)).hasSize(1);

    when(searchQueries.list(13L)).thenReturn(List.of(query));
    when(searchQueries.history(5L)).thenReturn(history(query, 2, List.of(point(TODAY, 2))));
    assertThat(service.repositoryQueriesWithHistory(13L, false, 0)).hasSize(1);
  }

  private static RankPoint point(LocalDate date, Integer position) {
    return new RankPoint(date, position, 1L);
  }

  private static SearchHistory history(SearchQuery query, Integer rank, List<RankPoint> points) {
    return new SearchHistory(
        query,
        rank,
        null,
        null,
        null,
        rank,
        "stars",
        points,
        Instant.now(),
        "SUCCESS",
        null,
        10,
        List.of());
  }
}
