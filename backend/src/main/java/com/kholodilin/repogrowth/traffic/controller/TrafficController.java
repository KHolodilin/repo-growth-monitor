package com.kholodilin.repogrowth.traffic.controller;

import com.kholodilin.repogrowth.common.api.ApiSurface;
import com.kholodilin.repogrowth.traffic.service.AdminTrafficService;
import com.kholodilin.repogrowth.traffic.service.TrafficReadService;
import com.kholodilin.repogrowth.traffic.service.TrafficReadService.DailyHistoryResponse;
import com.kholodilin.repogrowth.traffic.service.TrafficReadService.SnapshotHistoryResponse;
import com.kholodilin.repogrowth.traffic.service.TrafficReadService.SnapshotResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unified Traffic resource reads ({@link ApiSurface#RESOURCE}). Daily traffic-history is separate
 * from rolling-window referrer-history / path-history — do not sum overlapping snapshots as days.
 */
@RestController
@RequestMapping("/api/v1")
public class TrafficController {

  private final TrafficReadService traffic;
  private final AdminTrafficService adminTraffic;

  public TrafficController(
      TrafficReadService traffic, AdminTrafficService adminTraffic) {
    this.traffic = traffic;
    this.adminTraffic = adminTraffic;
  }

  @GetMapping("/owners/{ownerId}/traffic/kpi")
  public TrafficKpiResponse ownerTrafficKpi(
      @PathVariable long ownerId, @RequestParam(defaultValue = "7d") String period) {
    return traffic.ownerKpi(ownerId, period);
  }

  @GetMapping("/repositories/{repositoryId}/traffic/kpi")
  public TrafficKpiResponse repositoryTrafficKpi(
      @PathVariable long repositoryId, @RequestParam(defaultValue = "7d") String period) {
    return traffic.repositoryKpi(repositoryId, period);
  }

  @GetMapping("/owners/{ownerId}/traffic-history")
  public DailyHistoryResponse ownerTrafficHistory(
      @PathVariable long ownerId, @RequestParam(defaultValue = "7d") String period) {
    return traffic.ownerDailyHistory(ownerId, period);
  }

  @GetMapping("/repositories/{repositoryId}/traffic-history")
  public DailyHistoryResponse repositoryTrafficHistory(
      @PathVariable long repositoryId, @RequestParam(defaultValue = "7d") String period) {
    return traffic.dailyHistory(repositoryId, period);
  }

  @GetMapping("/repositories/{repositoryId}/referrers")
  public SnapshotResponse referrers(@PathVariable long repositoryId) {
    return traffic.latestReferrers(repositoryId);
  }

  @GetMapping("/repositories/{repositoryId}/paths")
  public SnapshotResponse paths(
      @PathVariable long repositoryId,
      @RequestParam(defaultValue = "false") boolean includeServicePaths) {
    return traffic.latestPaths(repositoryId, includeServicePaths);
  }

  @GetMapping("/repositories/{repositoryId}/referrer-history")
  public SnapshotHistoryResponse referrerHistory(
      @PathVariable long repositoryId,
      @RequestParam(required = false) String period,
      @RequestParam(required = false) Integer days) {
    return traffic.referrerHistory(repositoryId, period, days);
  }

  @GetMapping("/repositories/{repositoryId}/path-history")
  public SnapshotHistoryResponse pathHistory(
      @PathVariable long repositoryId,
      @RequestParam(required = false) String period,
      @RequestParam(required = false) Integer days,
      @RequestParam(defaultValue = "false") boolean includeServicePaths) {
    return traffic.pathHistory(repositoryId, period, days, includeServicePaths);
  }

  /**
   * Legacy composite traffic card used by Repository Details. Prefer KPI/referrers/paths once FE
   * consumes those shapes; no {@code /admin} prefix.
   */
  @GetMapping("/repositories/{repositoryId}/traffic")
  public AdminTrafficService.RepositoryTrafficSnapshot repositoryTraffic(
      @PathVariable long repositoryId,
      @RequestParam(defaultValue = "7d") String period,
      @RequestParam(defaultValue = "false") boolean includeServicePaths) {
    return adminTraffic.traffic(repositoryId, period, includeServicePaths);
  }

  /**
   * Legacy combined referrer/path history ({@code ?kind=}). Prefer referrer-history / path-history.
   */
  @GetMapping("/repositories/{repositoryId}/traffic/snapshot-history")
  public AdminTrafficService.SnapshotHistoryResponse repositorySnapshotHistory(
      @PathVariable long repositoryId,
      @RequestParam(required = false) String kind,
      @RequestParam(required = false) Integer days,
      @RequestParam(required = false) String period,
      @RequestParam(defaultValue = "false") boolean includeServicePaths) {
    return adminTraffic.snapshotHistory(repositoryId, kind, period, days, includeServicePaths);
  }
}
