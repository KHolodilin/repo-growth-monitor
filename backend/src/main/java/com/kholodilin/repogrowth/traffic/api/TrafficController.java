package com.kholodilin.repogrowth.traffic.api;

import com.kholodilin.repogrowth.common.api.ApiSurface;
import com.kholodilin.repogrowth.traffic.application.TrafficReadService;
import com.kholodilin.repogrowth.traffic.application.TrafficReadService.DailyHistoryResponse;
import com.kholodilin.repogrowth.traffic.application.TrafficReadService.SnapshotHistoryResponse;
import com.kholodilin.repogrowth.traffic.application.TrafficReadService.SnapshotResponse;
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

  public TrafficController(TrafficReadService traffic) {
    this.traffic = traffic;
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
}
