package com.kholodilin.repogrowth.traffic.api;

import com.kholodilin.repogrowth.auth.security.ResourceAccessPolicy;
import com.kholodilin.repogrowth.common.api.ApiSurface;
import com.kholodilin.repogrowth.common.api.PeriodParser;
import com.kholodilin.repogrowth.common.model.kpi.DataStatus;
import com.kholodilin.repogrowth.common.model.kpi.KpiScope;
import com.kholodilin.repogrowth.common.model.kpi.KpiValue;
import com.kholodilin.repogrowth.common.model.kpi.Unit;
import com.kholodilin.repogrowth.common.model.period.ComparisonRange;
import com.kholodilin.repogrowth.common.model.period.DateRange;
import com.kholodilin.repogrowth.common.model.period.Period;
import com.kholodilin.repogrowth.common.model.period.PeriodRanges;
import com.kholodilin.repogrowth.publication.PublicationService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 1 skeleton for unified Traffic KPI reads ({@link ApiSurface#RESOURCE}). Full history/KPI
 * parity lands in later Traffic phase; this endpoint locks the typed contract and access fence.
 */
@RestController
@RequestMapping("/api/v1")
public class TrafficKpiController {

  private final ResourceAccessPolicy access;
  private final Clock clock;

  public TrafficKpiController(ResourceAccessPolicy access, Clock clock) {
    this.access = access;
    this.clock = clock;
  }

  @GetMapping("/repositories/{repositoryId}/traffic/kpi")
  public TrafficKpiResponse repositoryTrafficKpi(
      @PathVariable long repositoryId, @RequestParam(defaultValue = "7d") String period) {
    access.requireReadable(repositoryId, PublicationService.Module.TRAFFIC);
    Period resolved = PeriodParser.parse(period);
    LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
    DateRange range = PeriodRanges.resolve(resolved, today, null);
    ComparisonRange comparison =
        resolved.allTime() ? null : PeriodRanges.comparison(range).orElse(null);
    KpiValue missing = new KpiValue(null, Unit.COUNT, DataStatus.MISSING, null, null, null);
    return new TrafficKpiResponse(
        KpiScope.REPOSITORY,
        Long.toString(repositoryId),
        resolved,
        range,
        comparison,
        new TrafficKpiResponse.Metrics(missing, missing, missing, missing));
  }
}
