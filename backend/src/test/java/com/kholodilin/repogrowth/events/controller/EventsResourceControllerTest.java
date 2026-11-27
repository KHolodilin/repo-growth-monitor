package com.kholodilin.repogrowth.events.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import com.kholodilin.repogrowth.events.model.GrowthEvent;
import com.kholodilin.repogrowth.events.model.GrowthEventSetting;
import com.kholodilin.repogrowth.events.repository.GrowthEventJdbcRepository;
import com.kholodilin.repogrowth.events.service.GrowthEventService;
import com.kholodilin.repogrowth.identity.security.CurrentUserAccess;
import com.kholodilin.repogrowth.identity.security.ResourceAccessPolicy;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EventsResourceControllerTest {

  @Mock ResourceAccessPolicy access;
  @Mock CurrentUserAccess currentUser;
  @Mock GrowthEventService events;
  @Mock GrowthEventJdbcRepository eventRepository;
  @Mock RepositoryService repositories;

  EventsResourceController controller;
  Repository repository;
  GrowthEvent event;

  @BeforeEach
  void setUp() {
    controller =
        new EventsResourceController(access, currentUser, events, eventRepository, repositories);
    Instant now = Instant.parse("2026-11-10T06:00:00Z");
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
    event =
        new GrowthEvent(
            5L,
            13L,
            now,
            "PROMOTION",
            "LINKEDIN_POST",
            "Launch",
            null,
            null,
            "MANUAL",
            null,
            now,
            now);
  }

  @Test
  void listsOwnerAndRepositoryEvents() {
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
    when(events.list(13L, "7d")).thenReturn(List.of(event));
    assertThat(controller.ownerEvents(1L, "7d")).containsExactly(event);

    doNothing().when(access).requireReadable(13L, PublicationService.Module.DASHBOARD);
    assertThat(controller.list(13L, "7d")).containsExactly(event);

    when(access.filterReadable(List.of(13L), PublicationService.Module.DASHBOARD))
        .thenReturn(List.of());
    assertThatThrownBy(() -> controller.ownerEvents(1L, "7d")).isInstanceOf(ApiException.class);
  }

  @Test
  void mutatesManualEventsAndProtectsSettings() {
    doNothing()
        .when(access)
        .requireAction(
            eq(13L),
            eq(ResourceAccessPolicy.ResourceAction.MUTATE),
            eq(PublicationService.Module.DASHBOARD));
    when(events.createManual(anyLong(), any(), any(), any(), any(), any())).thenReturn(event);
    assertThat(
            controller.create(
                13L,
                new EventsResourceController.ManualEventRequest(
                    "LINKEDIN_POST", Instant.parse("2026-11-10T06:00:00Z"), "Launch", null, null)))
        .isEqualTo(event);

    when(eventRepository.findById(5L)).thenReturn(Optional.of(event));
    when(events.updateManual(anyLong(), any(), any(), any(), any(), any())).thenReturn(event);
    assertThat(
            controller.update(
                5L,
                new EventsResourceController.ManualEventRequest(
                    "LINKEDIN_POST", Instant.parse("2026-11-10T06:00:00Z"), "Launch", null, null)))
        .isEqualTo(event);
    controller.delete(5L);
    verify(events).deleteManual(5L);

    when(currentUser.canSeePrivateRepositories()).thenReturn(true);
    when(repositories.get(13L)).thenReturn(repository);
    when(events.settings(13L))
        .thenReturn(List.of(new GrowthEventSetting(13L, "STAR_MILESTONE", true, null, null)));
    assertThat(controller.settings(13L)).hasSize(1);
    when(events.updateSettings(anyLong(), any())).thenReturn(List.of());
    assertThat(
            controller.updateSettings(
                13L,
                List.of(new EventsResourceController.SettingUpdateRequest("STAR_MILESTONE", false))))
        .isEmpty();

    when(currentUser.canSeePrivateRepositories()).thenReturn(false);
    when(currentUser.authenticated()).thenReturn(false);
    assertThatThrownBy(() -> controller.settings(13L)).isInstanceOf(ApiException.class);
    when(currentUser.authenticated()).thenReturn(true);
    assertThatThrownBy(() -> controller.settings(13L)).isInstanceOf(ApiException.class);
    when(eventRepository.findById(9L)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> controller.delete(9L)).isInstanceOf(ApiException.class);
  }
}
