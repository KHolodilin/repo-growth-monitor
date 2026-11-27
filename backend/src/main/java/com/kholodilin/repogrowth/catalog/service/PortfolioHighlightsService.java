package com.kholodilin.repogrowth.catalog.service;

import com.kholodilin.repogrowth.search.service.PortfolioHighlightMath;

import com.kholodilin.repogrowth.common.model.period.DashboardPeriod;

import com.kholodilin.repogrowth.catalog.service.DashboardResponse.Highlights;
import com.kholodilin.repogrowth.catalog.service.DashboardResponse.RepositoryRow;
import com.kholodilin.repogrowth.search.service.PortfolioHighlightMath.VisitorRow;
import com.kholodilin.repogrowth.events.service.GrowthEventService;
import com.kholodilin.repogrowth.search.service.SearchQueryService;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class PortfolioHighlightsService {

  private final SearchQueryService searchQueries;
  private final GrowthEventService events;

  public PortfolioHighlightsService(SearchQueryService searchQueries, GrowthEventService events) {
    this.searchQueries = searchQueries;
    this.events = events;
  }

  public Highlights build(DashboardPeriod period, List<RepositoryRow> rows) {
    var ranks = searchQueries.successfulPositions(period.to());
    return new Highlights(
        PortfolioHighlightMath.strongestRise(ranks, period.from()),
        PortfolioHighlightMath.worstDrop(ranks, period.from()),
        PortfolioHighlightMath.promotionEvents(
            events.promotionCounts(period.from(), period.to())),
        PortfolioHighlightMath.strongestVisitorGrowth(
            rows.stream()
                .map(row ->
                    new VisitorRow(row.id(), row.fullName(), row.visitors(), row.growthPercent()))
                .toList()));
  }
}
