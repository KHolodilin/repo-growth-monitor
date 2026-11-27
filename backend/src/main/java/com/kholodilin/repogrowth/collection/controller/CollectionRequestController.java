package com.kholodilin.repogrowth.collection.controller;

import com.kholodilin.repogrowth.collection.planner.PlanningWindow;
import com.kholodilin.repogrowth.collection.service.CollectionRequestService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-only repository discovery refresh. Per-repository collect aliases removed; use {@link
 * CollectionResourceController} {@code POST /api/v1/repositories/{id}/collections}.
 */
@RestController
@ConditionalOnProperty(name = "rgm.orchestration.enabled", havingValue = "true")
@RequestMapping("/api/v1/admin/repositories")
public class CollectionRequestController {
  private final PlanningWindow window;
  @org.springframework.beans.factory.annotation.Autowired private CollectionRequestService requests;

  public CollectionRequestController(PlanningWindow window) {
    this.window = window;
  }

  @PostMapping("/refresh")
  public ResponseEntity<java.util.List<CollectionRequestService.Created>> refresh() {
    return ResponseEntity.status(HttpStatus.CREATED).body(requests.refresh(window.businessDate()));
  }
}
