package com.kholodilin.repogrowth.topic.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

import com.kholodilin.repogrowth.administration.service.PublicationService;
import com.kholodilin.repogrowth.auth.security.ResourceAccessPolicy;
import com.kholodilin.repogrowth.catalog.model.GitHubOwner;
import com.kholodilin.repogrowth.catalog.model.OwnerType;
import com.kholodilin.repogrowth.catalog.model.Repository;
import com.kholodilin.repogrowth.catalog.service.RepositoryService;
import com.kholodilin.repogrowth.common.api.ApiException;
import com.kholodilin.repogrowth.common.model.kpi.KpiScope;
import com.kholodilin.repogrowth.topic.application.TopicVisibilityService.RankPoint;
import com.kholodilin.repogrowth.topic.application.TopicVisibilityService.TopicHistory;
import com.kholodilin.repogrowth.topic.domain.TopicWatch;
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
class TopicsKpiServiceTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 11, 10);
  private static final Clock CLOCK =
      Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

  @Mock ResourceAccessPolicy access;
  @Mock RepositoryService repositories;
  @Mock TopicVisibilityService topics;

  TopicsKpiService service;
  TopicWatch watch;
  Repository repository;

  @BeforeEach
  void setUp() {
    service = new TopicsKpiService(access, repositories, topics, CLOCK);
    Instant now = Instant.parse("2026-11-01T00:00:00Z");
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
  void repositoryKpiUsesCarryForwardBands() {
    doNothing().when(access).requireReadable(13L, PublicationService.Module.TOPICS);
    when(topics.visibility(13L, "all"))
        .thenReturn(
            List.of(
                history(
                    List.of(
                        new RankPoint(TODAY.minusDays(2), 8, 1L),
                        new RankPoint(TODAY.minusDays(1), 5, 2L)))));
    var kpi = service.repositoryKpi(13L, "7d", "all");
    assertThat(kpi.scope()).isEqualTo(KpiScope.REPOSITORY);
    assertThat(kpi.metrics().top10().value()).isNotNull();
  }

  @Test
  void ownerKpiAndWatchListRejectEmptyOwners() {
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
    when(topics.visibility(13L, "language"))
        .thenReturn(List.of(history(List.of(new RankPoint(TODAY, 4, 1L)))));
    assertThat(service.ownerKpi(1L, "7d", "language").scope()).isEqualTo(KpiScope.OWNER);

    doNothing().when(access).requireReadable(13L, PublicationService.Module.TOPICS);
    assertThat(service.repositoryWatchesWithHistory(13L, "language", true, 5)).hasSize(1);
    when(topics.visibility(13L, "all"))
        .thenReturn(List.of(history(List.of(new RankPoint(TODAY, 4, 1L)))));
    assertThat(service.repositoryWatchesWithHistory(13L, " ", false, 5)).hasSize(1);

    when(access.filterReadable(List.of(13L), PublicationService.Module.TOPICS))
        .thenReturn(List.of());
    assertThatThrownBy(() -> service.ownerKpi(1L, "7d", "all")).isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> service.repositoryKpi(13L, "7d", "nope"))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void missingRanksSurfaceMissingKpiValues() {
    doNothing().when(access).requireReadable(13L, PublicationService.Module.TOPICS);
    when(topics.visibility(13L, "all")).thenReturn(List.of(history(List.of())));
    var kpi = service.repositoryKpi(13L, "7d", null);
    assertThat(kpi.metrics().top3().value()).isNull();
  }

  private TopicHistory history(List<RankPoint> points) {
    return new TopicHistory(
        watch, 5, null, null, null, 5, points, Instant.now(), "SUCCESS", 20, List.of());
  }
}
