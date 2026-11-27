package com.kholodilin.repogrowth.search.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kholodilin.repogrowth.catalog.service.RepositoryService;
import com.kholodilin.repogrowth.common.api.ApiException;
import com.kholodilin.repogrowth.common.config.SearchProperties;
import com.kholodilin.repogrowth.search.domain.SearchQuery;
import com.kholodilin.repogrowth.search.domain.SearchRun;
import com.kholodilin.repogrowth.search.domain.SearchRunStatus;
import com.kholodilin.repogrowth.search.persistence.SearchQueryJdbcRepository;
import com.kholodilin.repogrowth.search.persistence.SearchResultJdbcRepository;
import com.kholodilin.repogrowth.search.persistence.SearchRunJdbcRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SearchQueryServiceUnitTest {
  private final SearchQueryJdbcRepository queries = mock(SearchQueryJdbcRepository.class);
  private final SearchRunJdbcRepository runs = mock(SearchRunJdbcRepository.class);
  private final SearchResultJdbcRepository results = mock(SearchResultJdbcRepository.class);
  private final RepositoryService repositories = mock(RepositoryService.class);
  private SearchQueryService service;
  private final Instant now = Instant.parse("2026-11-22T12:00:00Z");

  @BeforeEach
  void setUp() {
    service =
        new SearchQueryService(
            queries,
            runs,
            results,
            repositories,
            new SearchProperties(
                1, 50, Duration.ofHours(1), 30, new SearchProperties.Activity(30, 180)),
            null);
  }

  @Test
  void createUpdateNormalizeAndChartHelpersCoverDefaults() {
    when(queries.insert(anyLong(), anyString(), anyString(), anyBoolean(), anyInt()))
        .thenAnswer(inv ->
            new SearchQuery(
                1L,
                inv.getArgument(0),
                inv.getArgument(1),
                inv.getArgument(2),
                inv.getArgument(3),
                inv.getArgument(4),
                now,
                now));
    when(queries.findById(1L))
        .thenReturn(Optional.of(new SearchQuery(1L, 9L, "name", "stars:>1", true, 50, now, now)));
    when(queries.findNormalized(anyLong(), anyString(), any())).thenReturn(Optional.empty());
    when(queries.update(anyLong(), anyString(), anyString(), anyBoolean(), anyInt()))
        .thenReturn(new SearchQuery(1L, 9L, "renamed", "stars:>2", false, 40, now, now));
    when(queries.findByRepository(9L))
        .thenReturn(List.of(new SearchQuery(1L, 9L, "name", "stars:>1", true, 50, now, now)));
    when(runs.snapshots(1L)).thenReturn(List.of());
    when(runs.missedDates(1L)).thenReturn(List.of());
    when(runs.latest(1L)).thenReturn(Optional.empty());

    assertThat(service.create(9L, null, "  stars:>1  ", null, null).name()).isEqualTo("stars:>1");
    assertThat(service.update(1L, "renamed", null, null, null).name()).isEqualTo("renamed");
    assertThatThrownBy(() -> SearchQueryService.normalizeQuery(" "))
        .isInstanceOf(ApiException.class);
    assertThat(SearchQueryService.chartSortMode(List.of(), null)).isEqualTo("best_match");
    SearchRun stars =
        new SearchRun(
            2L,
            1L,
            9L,
            LocalDate.of(2026, 11, 22),
            SearchRunStatus.SUCCESS,
            1,
            null,
            null,
            null,
            now,
            now,
            now,
            10,
            1,
            null,
            "stars",
            null,
            null);
    assertThat(SearchQueryService.chartSortMode(List.of(stars), stars, "best_match"))
        .isEqualTo("stars");
    assertThat(service.visibility(9L)).hasSize(1);
    verify(repositories, org.mockito.Mockito.atLeastOnce()).get(9L);
  }
}
