package com.kholodilin.repogrowth.stats.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import com.kholodilin.repogrowth.stats.service.AdminStatsHistoryService;
import com.kholodilin.repogrowth.stats.service.StatsReadService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StatsControllerTest {

  @Mock ResourceAccessPolicy access;
  @Mock RepositoryService repositories;
  @Mock StatsReadService stats;
  @Mock AdminStatsHistoryService adminStatsHistory;

  StatsController controller;
  Repository repository;

  @BeforeEach
  void setUp() {
    controller = new StatsController(access, repositories, stats, adminStatsHistory);
    Instant now = Instant.parse("2026-11-01T00:00:00Z");
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
  void delegatesEveryStatsResourceRead() {
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
    doNothing().when(access).requireReadable(13L, PublicationService.Module.DASHBOARD);
    when(stats.ownerKpi(eq(1L), eq(List.of(13L)), eq("7d"))).thenReturn(null);
    when(stats.repositoryKpi(13L, "7d")).thenReturn(null);
    when(stats.ownerHistoryResponse(eq(1L), eq(List.of(13L)), eq("7d"))).thenReturn(null);
    when(adminStatsHistory.statsHistory(13L, "7d")).thenReturn(null);
    controller.ownerStatsKpi(1L, "7d");
    controller.repositoryStatsKpi(13L, "7d");
    controller.ownerStatsHistory(1L, "7d");
    controller.repositoryStatsHistory(13L, "7d");
    verify(adminStatsHistory).statsHistory(13L, "7d");
  }

  @Test
  void ownerEndpointsRejectWhenNoReadableRepositories() {
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
    assertThatThrownBy(() -> controller.ownerStatsKpi(1L, "7d"))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("Owner not found");
  }
}
