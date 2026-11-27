package com.kholodilin.repogrowth.event.api;

import com.kholodilin.repogrowth.administration.service.PublicationService;
import com.kholodilin.repogrowth.auth.security.CurrentUserAccess;
import com.kholodilin.repogrowth.auth.security.ResourceAccessPolicy;
import com.kholodilin.repogrowth.catalog.model.Repository;
import com.kholodilin.repogrowth.catalog.service.RepositoryService;
import com.kholodilin.repogrowth.common.api.ApiException;
import com.kholodilin.repogrowth.common.api.ApiSurface;
import com.kholodilin.repogrowth.event.application.GrowthEventService;
import com.kholodilin.repogrowth.event.domain.GrowthEvent;
import com.kholodilin.repogrowth.event.domain.GrowthEventSetting;
import com.kholodilin.repogrowth.event.persistence.GrowthEventJdbcRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unified Events resource API ({@link ApiSurface#RESOURCE}). Event settings are admin-only — never
 * exposed as public reads by default.
 */
@RestController
@RequestMapping("/api/v1")
public class EventsResourceController {

  private final ResourceAccessPolicy access;
  private final CurrentUserAccess currentUser;
  private final GrowthEventService events;
  private final GrowthEventJdbcRepository eventRepository;
  private final RepositoryService repositories;

  public EventsResourceController(
      ResourceAccessPolicy access,
      CurrentUserAccess currentUser,
      GrowthEventService events,
      GrowthEventJdbcRepository eventRepository,
      RepositoryService repositories) {
    this.access = access;
    this.currentUser = currentUser;
    this.events = events;
    this.eventRepository = eventRepository;
    this.repositories = repositories;
  }

  @GetMapping("/owners/{ownerId}/growth-events")
  public List<GrowthEvent> ownerEvents(
      @PathVariable long ownerId, @RequestParam(defaultValue = "7d") String period) {
    repositories.owner(ownerId);
    List<Long> readable =
        access.filterReadable(
            repositories.list(false).stream()
                .filter(repo -> repo.ownerId() == ownerId)
                .map(Repository::id)
                .toList(),
            PublicationService.Module.DASHBOARD);
    if (readable.isEmpty()) {
      throw ApiException.notFound("Owner not found");
    }
    List<GrowthEvent> combined = new ArrayList<>();
    for (Long repositoryId : readable) {
      combined.addAll(events.list(repositoryId, period));
    }
    combined.sort(Comparator.comparing(GrowthEvent::eventAt).reversed());
    return combined;
  }

  @GetMapping("/repositories/{repositoryId}/growth-events")
  public List<GrowthEvent> list(
      @PathVariable long repositoryId, @RequestParam(defaultValue = "7d") String period) {
    access.requireReadable(repositoryId, PublicationService.Module.DASHBOARD);
    return events.list(repositoryId, period);
  }

  @PostMapping("/repositories/{repositoryId}/growth-events")
  public GrowthEvent create(
      @PathVariable long repositoryId,
      @RequestBody GrowthEventController.ManualEventRequest request) {
    access.requireAction(
        repositoryId,
        ResourceAccessPolicy.ResourceAction.MUTATE,
        PublicationService.Module.DASHBOARD);
    return events.createManual(
        repositoryId,
        request.type(),
        request.eventAt(),
        request.title(),
        request.url(),
        request.description());
  }

  @PutMapping("/growth-events/{eventId}")
  public GrowthEvent update(
      @PathVariable long eventId, @RequestBody GrowthEventController.ManualEventRequest request) {
    GrowthEvent existing = requireEvent(eventId);
    access.requireAction(
        existing.repositoryId(),
        ResourceAccessPolicy.ResourceAction.MUTATE,
        PublicationService.Module.DASHBOARD);
    return events.updateManual(
        eventId,
        request.type(),
        request.eventAt(),
        request.title(),
        request.url(),
        request.description());
  }

  @DeleteMapping("/growth-events/{eventId}")
  public void delete(@PathVariable long eventId) {
    GrowthEvent existing = requireEvent(eventId);
    access.requireAction(
        existing.repositoryId(),
        ResourceAccessPolicy.ResourceAction.MUTATE,
        PublicationService.Module.DASHBOARD);
    events.deleteManual(eventId);
  }

  /**
   * Detection settings stay private to operators. Anonymous/public callers always get 404 — never a
   * settings payload.
   */
  @GetMapping("/repositories/{repositoryId}/growth-event-settings")
  public List<GrowthEventSetting> settings(@PathVariable long repositoryId) {
    requireSettingsAccess(repositoryId);
    return events.settings(repositoryId);
  }

  @PutMapping("/repositories/{repositoryId}/growth-event-settings")
  public List<GrowthEventSetting> updateSettings(
      @PathVariable long repositoryId,
      @RequestBody List<GrowthEventController.SettingUpdateRequest> settings) {
    requireSettingsAccess(repositoryId);
    return events.updateSettings(
        repositoryId,
        settings.stream()
            .map(item ->
                new GrowthEventSetting(repositoryId, item.eventType(), item.enabled(), null, null))
            .toList());
  }

  private void requireSettingsAccess(long repositoryId) {
    if (!currentUser.canSeePrivateRepositories()) {
      if (!currentUser.authenticated()) {
        throw ApiException.unauthorized("Authentication required");
      }
      throw ApiException.forbidden("Growth event settings are not public");
    }
    repositories.get(repositoryId);
  }

  private GrowthEvent requireEvent(long eventId) {
    return eventRepository
        .findById(eventId)
        .orElseThrow(() -> ApiException.notFound("Growth event not found"));
  }
}
