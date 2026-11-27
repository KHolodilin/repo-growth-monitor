package com.kholodilin.repogrowth.traffic.controller;

import com.kholodilin.repogrowth.administration.service.PublicationService;
import com.kholodilin.repogrowth.catalog.model.Repository;
import com.kholodilin.repogrowth.catalog.service.RepositoryService;
import com.kholodilin.repogrowth.common.api.ApiException;
import com.kholodilin.repogrowth.common.api.ApiSurface;
import com.kholodilin.repogrowth.identity.security.ResourceAccessPolicy;
import com.kholodilin.repogrowth.traffic.service.AdminTrafficService;
import com.kholodilin.repogrowth.traffic.service.TrafficKpiResponse;
import com.kholodilin.repogrowth.traffic.service.TrafficReadService;
import com.kholodilin.repogrowth.traffic.service.TrafficReadService.DailyHistoryResponse;
import com.kholodilin.repogrowth.traffic.service.TrafficReadService.SnapshotHistoryResponse;
import com.kholodilin.repogrowth.traffic.service.TrafficReadService.SnapshotResponse;
import java.util.List;
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

  private final ResourceAccessPolicy access;
  private final RepositoryService repositories;
  private final TrafficReadService traffic;
  private final AdminTrafficService adminTraffic;

  public TrafficController(
      ResourceAccessPolicy access,
      RepositoryService repositories,
      TrafficReadService traffic,
      AdminTrafficService adminTraffic) {
    this.access = access;
    this.repositories = repositories;
    this.traffic = traffic;
    this.adminTraffic = adminTraffic;
  }

  @GetMapping("/owners/{ownerId}/traffic/kpi")
  public TrafficKpiResponse ownerTrafficKpi(
      @PathVariable long ownerId, @RequestParam(defaultValue = "7d") String period) {
    return traffic.ownerKpi(
        ownerId, readableOwnerRepositories(ownerId, PublicationService.Module.TRAFFIC), period);
  }

  @GetMapping("/repositories/{repositoryId}/traffic/kpi")
  public TrafficKpiResponse repositoryTrafficKpi(
      @PathVariable long repositoryId, @RequestParam(defaultValue = "7d") String period) {
    access.requireReadable(repositoryId, PublicationService.Module.TRAFFIC);
    return traffic.repositoryKpi(repositoryId, period);
  }

  @GetMapping("/owners/{ownerId}/traffic-history")
  public DailyHistoryResponse ownerTrafficHistory(
      @PathVariable long ownerId, @RequestParam(defaultValue = "7d") String period) {
    return traffic.ownerDailyHistory(
        ownerId, readableOwnerRepositories(ownerId, PublicationService.Module.TRAFFIC), period);
  }

  @GetMapping("/repositories/{repositoryId}/traffic-history")
  public DailyHistoryResponse repositoryTrafficHistory(
      @PathVariable long repositoryId, @RequestParam(defaultValue = "7d") String period) {
    access.requireReadable(repositoryId, PublicationService.Module.TRAFFIC);
    return traffic.dailyHistory(repositoryId, period);
  }

  @GetMapping("/repositories/{repositoryId}/referrers")
  public SnapshotResponse referrers(@PathVariable long repositoryId) {
    access.requireReadable(repositoryId, PublicationService.Module.REFERRERS);
    return traffic.latestReferrers(repositoryId);
  }

  @GetMapping("/repositories/{repositoryId}/paths")
  public SnapshotResponse paths(
      @PathVariable long repositoryId,
      @RequestParam(defaultValue = "false") boolean includeServicePaths) {
    access.requireReadable(repositoryId, PublicationService.Module.PATHS);
    return traffic.latestPaths(repositoryId, includeServicePaths);
  }

  @GetMapping("/repositories/{repositoryId}/referrer-history")
  public SnapshotHistoryResponse referrerHistory(
      @PathVariable long repositoryId,
      @RequestParam(required = false) String period,
      @RequestParam(required = false) Integer days) {
    access.requireReadable(repositoryId, PublicationService.Module.REFERRERS);
    return traffic.referrerHistory(repositoryId, period, days);
  }

  @GetMapping("/repositories/{repositoryId}/path-history")
  public SnapshotHistoryResponse pathHistory(
      @PathVariable long repositoryId,
      @RequestParam(required = false) String period,
      @RequestParam(required = false) Integer days,
      @RequestParam(defaultValue = "false") boolean includeServicePaths) {
    access.requireReadable(repositoryId, PublicationService.Module.PATHS);
    return traffic.pathHistory(repositoryId, period, days, includeServicePaths);
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
    PublicationService.Module module =
        kind != null && kind.equalsIgnoreCase("paths")
            ? PublicationService.Module.PATHS
            : PublicationService.Module.REFERRERS;
    access.requireReadable(repositoryId, module);
    return adminTraffic.snapshotHistory(repositoryId, kind, period, days, includeServicePaths);
  }

  private List<Long> readableOwnerRepositories(long ownerId, PublicationService.Module module) {
    repositories.owner(ownerId);
    List<Long> readable =
        access.filterReadable(
            repositories.list(false).stream()
                .filter(repo -> repo.ownerId() == ownerId)
                .map(Repository::id)
                .toList(),
            module);
    if (readable.isEmpty()) {
      throw ApiException.notFound("Owner not found");
    }
    return readable;
  }
}
