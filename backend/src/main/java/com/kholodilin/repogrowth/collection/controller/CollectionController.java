package com.kholodilin.repogrowth.collection.controller;

import com.kholodilin.repogrowth.collection.planner.PlanningWindow;
import com.kholodilin.repogrowth.common.api.ApiException;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ops-only collection commands. Resource reads/runs live on {@link CollectionRunResourceController}
 * / {@link CollectionResourceController}; no GET surface here. Manual planner trigger removed per
 * modular-api-spec §7 (scheduled planning remains).
 */
@RestController
@RequestMapping("/api/v1/admin")
public class CollectionController {

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private com.kholodilin.repogrowth.collection.service.CollectionRequestService commands;

  private final PlanningWindow planningWindow;

  public CollectionController(PlanningWindow planningWindow) {
    this.planningWindow = planningWindow;
  }

  @org.springframework.web.bind.annotation.ResponseStatus(
      org.springframework.http.HttpStatus.CREATED)
  @PostMapping("/repositories/{id}/collect")
  public Object collect(@PathVariable long id) {
    if (commands == null) {
      throw ApiException.serviceUnavailable("Collector orchestration is required");
    }
    return commands.manual(id, planningWindow.businessDate());
  }
}
