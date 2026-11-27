package com.kholodilin.repogrowth.collection.controller;

import com.kholodilin.repogrowth.collection.model.CollectionRun;
import com.kholodilin.repogrowth.collection.planner.DailyPlanner;
import com.kholodilin.repogrowth.collection.planner.PlanningWindow;
import com.kholodilin.repogrowth.collection.repository.CollectionRunJdbcRepository;
import com.kholodilin.repogrowth.collection.service.CollectionRunMapper;
import com.kholodilin.repogrowth.collection.service.CollectionRunMapper.CollectionRunResponse;
import com.kholodilin.repogrowth.common.api.ApiException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
public class CollectionController {

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private com.kholodilin.repogrowth.collection.service.CollectionRequestService commands;

  private final PlanningWindow planningWindow;
  private final DailyPlanner dailyPlanner;
  private final CollectionRunJdbcRepository runRepository;
  private final CollectionRunMapper runMapper;

  public CollectionController(
      PlanningWindow planningWindow,
      DailyPlanner dailyPlanner,
      CollectionRunJdbcRepository runRepository,
      CollectionRunMapper runMapper) {
    this.planningWindow = planningWindow;
    this.dailyPlanner = dailyPlanner;
    this.runRepository = runRepository;
    this.runMapper = runMapper;
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

  /**
   * @deprecated Ops-only planner trigger; prefer scheduled planning. Kept while Auth/security tests
   *     still assert the admin surface — remove once consumers are zero (phase 7).
   */
  @Deprecated
  @org.springframework.web.bind.annotation.ResponseStatus(
      org.springframework.http.HttpStatus.CREATED)
  @PostMapping("/collection/plan")
  public DailyPlanner.PlannerResult plan() {
    return dailyPlanner.planNow(true);
  }

  @GetMapping("/collection-runs/{id}")
  public CollectionRunResponse getRun(@PathVariable long id) {
    CollectionRun run =
        runRepository
            .findById(id)
            .orElseThrow(() -> ApiException.notFound("Collection run not found"));
    return runMapper.toResponse(runMapper.toView(run));
  }
}
