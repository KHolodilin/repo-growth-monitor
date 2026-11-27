package com.kholodilin.repogrowth.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kholodilin.repogrowth.catalog.model.Repository;
import com.kholodilin.repogrowth.catalog.repository.GitHubOwnerJdbcRepository;
import com.kholodilin.repogrowth.catalog.repository.RepositoryJdbcRepository;
import com.kholodilin.repogrowth.collection.planner.PlanningWindow;
import com.kholodilin.repogrowth.collection.service.CollectionRequestService;
import com.kholodilin.repogrowth.common.api.ApiException;
import com.kholodilin.repogrowth.common.observability.AppMetrics;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

class RepositoryServiceAccessTest {

  private final LocalDate date = LocalDate.of(2026, 10, 20);
  private final Instant now = Instant.parse("2026-10-20T06:00:00Z");
  private RepositoryJdbcRepository repositories;
  private AppMetrics metrics;
  private CollectionRequestService commands;
  private RepositoryService service;

  @BeforeEach
  void setUp() {
    repositories = mock(RepositoryJdbcRepository.class);
    metrics = mock(AppMetrics.class);
    commands = mock(CollectionRequestService.class);
    PlanningWindow window = mock(PlanningWindow.class);
    when(window.businessDate()).thenReturn(date);
    service =
        new RepositoryService(mock(GitHubOwnerJdbcRepository.class), repositories, metrics, window);
  }

  @Test
  void refreshAsksForTheCollectorEndpointWhenOrchestrationIsAvailable() {
    ReflectionTestUtils.setField(service, "commands", commands);

    assertThatThrownBy(() -> service.list(true))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("POST /api/v1/admin/repositories/refresh");
    verify(repositories, never()).findListed();
  }

  @Test
  void refreshRequiresCollectorOrchestrationWhenItIsNotAvailable() {
    assertThatThrownBy(() -> service.list(true))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("Collector orchestration is required");
    verify(repositories, never()).findListed();
  }

  @Test
  void listReadsStoredRepositoriesAndReportsTheTrackedCount() {
    Repository stored = repository();
    when(repositories.findListed()).thenReturn(List.of(stored));
    when(repositories.countTracked()).thenReturn(1);

    assertThat(service.list(false)).containsExactly(stored);
    verify(metrics).setTrackedRepositories(1);
  }

  @Test
  void trackingNeedsCollectorOrchestrationBeforeAnythingIsChanged() {
    when(repositories.findById(13L)).thenReturn(Optional.of(repository()));

    assertThatThrownBy(() -> service.setTracking(13L, true))
        .isInstanceOfSatisfying(
            ApiException.class,
            ex -> {
              assertThat(ex.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
              assertThat(ex.getMessage()).contains("Collector orchestration is required");
            });
    verify(repositories, never()).setTracking(13L, true);
  }

  @Test
  void trackingRejectsARepositoryTheTokenCannotSee() {
    ReflectionTestUtils.setField(service, "commands", commands);
    when(repositories.findById(13L)).thenReturn(Optional.of(repository()));
    when(repositories.isAccountAccessible(13L)).thenReturn(false);

    assertThatThrownBy(() -> service.setTracking(13L, true))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("Only repositories available");
    verify(repositories, never()).setTracking(13L, true);
    verify(commands, never()).manual(13L, date);
  }

  @Test
  void enablingTrackingStartsAManualCollection() {
    ReflectionTestUtils.setField(service, "commands", commands);
    Repository tracked = repository();
    when(repositories.findById(13L)).thenReturn(Optional.of(tracked));
    when(repositories.isAccountAccessible(13L)).thenReturn(true);
    when(repositories.setTracking(13L, true)).thenReturn(tracked);

    assertThat(service.setTracking(13L, true)).isEqualTo(tracked);
    verify(commands).manual(13L, date);
  }

  @Test
  void disablingTrackingDoesNotNeedTheCollector() {
    Repository stored = repository();
    when(repositories.findById(13L)).thenReturn(Optional.of(stored));
    when(repositories.setTracking(13L, false)).thenReturn(stored);

    assertThat(service.setTracking(13L, false)).isEqualTo(stored);
    verify(commands, never()).manual(13L, date);
    verify(repositories, never()).isAccountAccessible(13L);
  }

  @Test
  void signalsAreReadFromStorage() {
    when(repositories.signalsFor(List.of())).thenReturn(Map.of());

    assertThat(service.signalsFor(List.of())).isEmpty();
    verify(repositories).signalsFor(List.of());
  }

  private Repository repository() {
    return new Repository(
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
        false,
        now,
        now,
        now,
        now,
        null,
        null,
        now,
        now);
  }
}
