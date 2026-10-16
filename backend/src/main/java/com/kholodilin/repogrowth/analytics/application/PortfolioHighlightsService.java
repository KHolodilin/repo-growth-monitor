package com.kholodilin.repogrowth.analytics.application;

import com.kholodilin.repogrowth.analytics.api.DashboardResponse.Highlights;
import com.kholodilin.repogrowth.analytics.api.DashboardResponse.RepositoryRow;
import com.kholodilin.repogrowth.analytics.application.PortfolioHighlightMath.VisitorRow;
import com.kholodilin.repogrowth.event.persistence.GrowthEventJdbcRepository;
import com.kholodilin.repogrowth.search.persistence.SearchRunJdbcRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PortfolioHighlightsService {

    private final SearchRunJdbcRepository searchRunRepository;
    private final GrowthEventJdbcRepository eventRepository;

    public PortfolioHighlightsService(
            SearchRunJdbcRepository searchRunRepository,
            GrowthEventJdbcRepository eventRepository
    ) {
        this.searchRunRepository = searchRunRepository;
        this.eventRepository = eventRepository;
    }

    public Highlights build(DashboardPeriod period, List<RepositoryRow> rows) {
        List<PortfolioHighlightMath.RankPoint> ranks = searchRunRepository.successfulPositions(period.to());
        return new Highlights(
                PortfolioHighlightMath.strongestRise(ranks, period.from()),
                PortfolioHighlightMath.worstDrop(ranks, period.from()),
                PortfolioHighlightMath.promotionEvents(eventRepository.promotionCounts(period.from(), period.to())),
                PortfolioHighlightMath.strongestVisitorGrowth(rows.stream()
                        .map(row -> new VisitorRow(row.id(), row.fullName(), row.visitors(), row.growthPercent()))
                        .toList())
        );
    }
}
