package com.kholodilin.repogrowth.administration.controller;

import com.kholodilin.repogrowth.administration.service.PublicationService;
import com.kholodilin.repogrowth.auth.security.ResourceAccessPolicy;
import com.kholodilin.repogrowth.catalog.model.Repository;
import com.kholodilin.repogrowth.catalog.service.RepositoryService;
import com.kholodilin.repogrowth.common.api.ApiSurface;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administration commands ({@link ApiSurface#OPS} / admin-gated). Tracking and publication go
 * through existing services; refresh/discovery stays on Collection orchestration — no GitHub scrape
 * here.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdministrationController {

  public record TrackingBody(@NotNull Boolean enabled) {}

  public record TrackingResult(String repositoryId, boolean trackingEnabled) {}

  private final ResourceAccessPolicy access;
  private final RepositoryService repositories;

  public AdministrationController(ResourceAccessPolicy access, RepositoryService repositories) {
    this.access = access;
    this.repositories = repositories;
  }

  /**
   * Preferred tracking verb for the administration module; legacy POST remains on
   * RepositoryController.
   */
  @PutMapping("/repositories/{repositoryId}/tracking")
  public TrackingResult tracking(
      @PathVariable long repositoryId, @Valid @RequestBody TrackingBody body) {
    access.requireAction(
        repositoryId,
        ResourceAccessPolicy.ResourceAction.MUTATE,
        PublicationService.Module.DASHBOARD);
    Repository updated = repositories.setTracking(repositoryId, body.enabled());
    return new TrackingResult(Long.toString(updated.id()), updated.trackingEnabled());
  }
}
