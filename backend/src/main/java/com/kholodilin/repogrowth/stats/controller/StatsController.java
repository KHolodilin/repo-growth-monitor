package com.kholodilin.repogrowth.stats.controller;

import com.kholodilin.repogrowth.common.api.ApiSurface;
import com.kholodilin.repogrowth.stats.service.AdminStatsHistoryService;
import com.kholodilin.repogrowth.stats.service.StatsReadService;
import com.kholodilin.repogrowth.stats.service.StatsReadService.StatsHistoryResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Unified Stats resource reads ({@link ApiSurface#RESOURCE}). */
@RestController
@RequestMapping("/api/v1")
public class StatsController {

  private final StatsReadService stats;
  private final AdminStatsHistoryService adminStatsHistory;

  public StatsController(
      StatsReadService stats, AdminStatsHistoryService adminStatsHistory) {
    this.stats = stats;
    this.adminStatsHistory = adminStatsHistory;
  }

  @GetMapping("/owners/{ownerId}/stats/kpi")
  public StatsKpiResponse ownerStatsKpi(
      @PathVariable long ownerId, @RequestParam(defaultValue = "7d") String period) {
    return stats.ownerKpi(ownerId, period);
  }

  @GetMapping("/repositories/{repositoryId}/stats/kpi")
  public StatsKpiResponse repositoryStatsKpi(
      @PathVariable long repositoryId, @RequestParam(defaultValue = "7d") String period) {
    return stats.repositoryKpi(repositoryId, period);
  }

  @GetMapping("/owners/{ownerId}/stats-history")
  public StatsHistoryResponse ownerStatsHistory(
      @PathVariable long ownerId, @RequestParam(defaultValue = "7d") String period) {
    return stats.ownerHistoryResponse(ownerId, period);
  }

  /**
   * Repository stats history in the FE composite shape (totals + points). Owner history keeps the
   * resource {@link StatsHistoryResponse} contract.
   */
  @GetMapping("/repositories/{repositoryId}/stats-history")
  public AdminStatsHistoryService.RepositoryStatsHistory repositoryStatsHistory(
      @PathVariable long repositoryId, @RequestParam(defaultValue = "7d") String period) {
    return adminStatsHistory.statsHistory(repositoryId, period);
  }
}
