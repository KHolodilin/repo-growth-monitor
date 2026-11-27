package com.kholodilin.repogrowth.catalog.controller;

import com.kholodilin.repogrowth.catalog.service.PortfolioDashboardService;
import com.kholodilin.repogrowth.collection.service.AdminCollectionProgress;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin portfolio/dashboard composite reads (legacy until FE finishes resource migration). */
@RestController
@RequestMapping("/api/v1/admin")
public class PortfolioDashboardController {

  @Autowired(required = false)
  private AdminCollectionProgress progress;

  private final PortfolioDashboardService dashboard;

  public PortfolioDashboardController(PortfolioDashboardService dashboard) {
    this.dashboard = dashboard;
  }

  @GetMapping("/dashboard")
  public Object dashboard(
      @RequestParam(defaultValue = "7d") String period,
      @RequestParam(required = false) String owner) {
    var result = dashboard.dashboard(period, owner);
    return progress == null ? result : progress.apply(result);
  }

  @GetMapping("/portfolio")
  public PortfolioDashboardService.PortfolioSnapshot portfolio(
      @RequestParam(defaultValue = "7d") String period) {
    return dashboard.portfolio(period);
  }
}
