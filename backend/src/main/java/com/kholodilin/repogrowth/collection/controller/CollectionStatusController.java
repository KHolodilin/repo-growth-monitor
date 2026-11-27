package com.kholodilin.repogrowth.collection.controller;

import com.kholodilin.repogrowth.collection.service.CollectionStatusService;
import com.kholodilin.repogrowth.identity.security.CurrentUserAccess;
import com.kholodilin.repogrowth.collection.service.CollectionStatusService.CollectionStatusResponse;
import com.kholodilin.repogrowth.common.api.ApiSurface;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Compact collection status for header UI ({@link ApiSurface#RESOURCE}). Layout must not poll the
 * full admin dashboard for progress.
 */
@RestController
@RequestMapping("/api/v1")
public class CollectionStatusController {

  private final CollectionStatusService status;
  private final CurrentUserAccess currentUser;

  public CollectionStatusController(
      CollectionStatusService status, CurrentUserAccess currentUser) {
    this.status = status;
    this.currentUser = currentUser;
  }

  @GetMapping("/collection-status")
  public CollectionStatusResponse collectionStatus(@RequestParam(required = false) String owner) {
    return status.status(owner, currentUser.canSeePrivateRepositories());
  }
}
