package com.kholodilin.repogrowth.collection.controller;

import com.kholodilin.repogrowth.collection.planner.PlanningWindow;
import com.kholodilin.repogrowth.credentials.service.GitHubAccessStatusService;
import java.time.format.DateTimeFormatter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/system")
public class SystemStatusController {

  private static final DateTimeFormatter HOUR_MINUTE = DateTimeFormatter.ofPattern("HH:mm");

  private final GitHubAccessStatusService accessStatus;
  private final PlanningWindow planningWindow;

  public SystemStatusController(
      GitHubAccessStatusService accessStatus, PlanningWindow planningWindow) {
    this.accessStatus = accessStatus;
    this.planningWindow = planningWindow;
  }

  @GetMapping("/status")
  public SystemStatusResponse status() {
    GitHubAccessStatusService.Status access = accessStatus.status();
    return new SystemStatusResponse(
        access.credentialsConfigured(), access.tokenState(), plannerWindow());
  }

  private SystemStatusResponse.PlannerWindow plannerWindow() {
    return new SystemStatusResponse.PlannerWindow(
        planningWindow.zoneId().getId(),
        planningWindow.from().format(HOUR_MINUTE),
        planningWindow.to().format(HOUR_MINUTE),
        planningWindow.isOpen());
  }

  public record SystemStatusResponse(
      boolean credentialsConfigured,
      GitHubAccessStatusService.TokenState tokenState,
      PlannerWindow planner) {
    public record PlannerWindow(String timezone, String from, String to, boolean open) {}
  }
}
