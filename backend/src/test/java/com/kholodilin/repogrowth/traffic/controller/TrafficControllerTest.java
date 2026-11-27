package com.kholodilin.repogrowth.traffic.controller;

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
import com.kholodilin.repogrowth.traffic.service.AdminTrafficService;
import com.kholodilin.repogrowth.traffic.service.TrafficReadService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TrafficControllerTest {

  @Mock ResourceAccessPolicy access;
  @Mock RepositoryService repositories;
  @Mock TrafficReadService traffic;
  @Mock AdminTrafficService adminTraffic;

  TrafficController controller;
  Repository repository;

  @BeforeEach
  void setUp() {
    controller = new TrafficController(access, repositories, traffic, adminTraffic);
    Instant now = Instant.parse("2026-11-01T00:00:00Z");
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
  }

  @Test
  void delegatesEveryTrafficResourceRead() {
    when(traffic.ownerKpi(eq(1L), eq(List.of(13L)), eq("7d"))).thenReturn(null);
    when(traffic.repositoryKpi(13L, "7d")).thenReturn(null);
    when(traffic.ownerDailyHistory(eq(1L), eq(List.of(13L)), eq("7d"))).thenReturn(null);
    when(traffic.dailyHistory(13L, "7d")).thenReturn(null);
    when(traffic.latestReferrers(13L)).thenReturn(null);
    when(traffic.latestPaths(13L, true)).thenReturn(null);
    when(traffic.referrerHistory(13L, "7d", null)).thenReturn(null);
    when(traffic.pathHistory(13L, "7d", null, false)).thenReturn(null);
    stubReadableOwner();
    doNothing().when(access).requireReadable(13L, PublicationService.Module.TRAFFIC);
    doNothing().when(access).requireReadable(13L, PublicationService.Module.REFERRERS);
    doNothing().when(access).requireReadable(13L, PublicationService.Module.PATHS);

    controller.ownerTrafficKpi(1L, "7d");
    controller.repositoryTrafficKpi(13L, "7d");
    controller.ownerTrafficHistory(1L, "7d");
    controller.repositoryTrafficHistory(13L, "7d");
    controller.referrers(13L);
    controller.paths(13L, true);
    controller.referrerHistory(13L, "7d", null);
    controller.pathHistory(13L, "7d", null, false);

    verify(traffic).pathHistory(13L, "7d", null, false);
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
    when(access.filterReadable(List.of(13L), PublicationService.Module.TRAFFIC))
        .thenReturn(List.of());
    assertThatThrownBy(() -> controller.ownerTrafficKpi(1L, "7d"))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("Owner not found");
  }

  private void stubReadableOwner() {
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
    when(access.filterReadable(List.of(13L), PublicationService.Module.TRAFFIC))
        .thenReturn(List.of(13L));
  }
}
