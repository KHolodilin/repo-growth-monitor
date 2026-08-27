package com.kholodilin.repogrowth.analytics.api;

import com.kholodilin.repogrowth.analytics.application.AnalyticsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    public AnalyticsController(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @GetMapping("/dashboard")
    public DashboardResponse dashboard(@RequestParam(defaultValue = "30d") String period) {
        return analyticsService.dashboard(period);
    }

    @GetMapping("/portfolio")
    public AnalyticsService.PortfolioSnapshot portfolio(@RequestParam(defaultValue = "30d") String period) {
        return analyticsService.portfolio(period);
    }

    @GetMapping("/repositories/{id}/traffic")
    public AnalyticsService.RepositoryTrafficSnapshot traffic(
            @PathVariable long id,
            @RequestParam(defaultValue = "30d") String period
    ) {
        return analyticsService.traffic(id, period);
    }
}
