package com.kholodilin.repogrowth.analytics.application;

import com.kholodilin.repogrowth.analytics.api.DashboardResponse.EventHighlight;
import com.kholodilin.repogrowth.analytics.api.DashboardResponse.RankHighlight;
import com.kholodilin.repogrowth.analytics.api.DashboardResponse.VisitorHighlight;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PortfolioHighlightMath {

    private PortfolioHighlightMath() {
    }

    public record RankPoint(long repositoryId, String fullName, String queryName, LocalDate date, int position) {
    }

    public record PromotionCount(long repositoryId, String fullName, int count) {
    }

    public record VisitorRow(long repositoryId, String fullName, Long visitors, Double growthPercent) {
    }

    public static RankHighlight strongestRise(List<RankPoint> points, LocalDate from) {
        return choose(points, from, true);
    }

    public static RankHighlight worstDrop(List<RankPoint> points, LocalDate from) {
        return choose(points, from, false);
    }

    public static EventHighlight promotionEvents(List<PromotionCount> counts) {
        if (counts.isEmpty()) {
            return null;
        }
        int total = 0;
        PromotionCount lead = null;
        List<String> names = new ArrayList<>();
        for (PromotionCount count : counts) {
            total += count.count();
            names.add(count.fullName());
            if (lead == null || count.count() > lead.count()
                    || (count.count() == lead.count() && count.fullName().compareTo(lead.fullName()) < 0)) {
                lead = count;
            }
        }
        names.sort(Comparator.naturalOrder());
        return new EventHighlight(total, lead.repositoryId(), lead.fullName(), List.copyOf(names));
    }

    public static VisitorHighlight strongestVisitorGrowth(List<VisitorRow> rows) {
        VisitorHighlight best = null;
        long bestVisitors = Long.MIN_VALUE;
        for (VisitorRow row : rows) {
            if (row.growthPercent() == null) {
                continue;
            }
            long visitors = row.visitors() == null ? 0 : row.visitors();
            boolean better = best == null
                    || row.growthPercent() > best.growthPercent()
                    || (row.growthPercent().equals(best.growthPercent()) && visitors > bestVisitors)
                    || (row.growthPercent().equals(best.growthPercent()) && visitors == bestVisitors
                    && row.fullName().compareTo(best.fullName()) < 0);
            if (better) {
                best = new VisitorHighlight(row.repositoryId(), row.fullName(), row.growthPercent());
                bestVisitors = visitors;
            }
        }
        return best;
    }

    private static RankHighlight choose(List<RankPoint> points, LocalDate from, boolean rise) {
        Map<String, List<RankPoint>> groups = new LinkedHashMap<>();
        for (RankPoint point : points) {
            groups.computeIfAbsent(point.repositoryId() + "\0" + point.queryName(), key -> new ArrayList<>()).add(point);
        }
        RankHighlight chosen = null;
        int chosenDelta = 0;
        for (List<RankPoint> group : groups.values()) {
            group.sort(Comparator.comparing(RankPoint::date));
            RankPoint baseline = null;
            RankPoint firstInWindow = null;
            RankPoint end = null;
            for (RankPoint point : group) {
                if (point.date().isBefore(from)) {
                    baseline = point;
                    continue;
                }
                if (firstInWindow == null) {
                    firstInWindow = point;
                }
                end = point;
            }
            if (end == null) {
                continue;
            }
            RankPoint start = baseline != null ? baseline : firstInWindow;
            if (start.date().equals(end.date())) {
                continue;
            }
            int delta = start.position() - end.position();
            if (rise && delta <= 0) {
                continue;
            }
            if (!rise && delta >= 0) {
                continue;
            }
            boolean better = chosen == null
                    || (rise && delta > chosenDelta)
                    || (!rise && delta < chosenDelta)
                    || (delta == chosenDelta && prefer(end, chosen, rise));
            if (better) {
                chosen = new RankHighlight(end.repositoryId(), end.fullName(), end.queryName(), start.position(), end.position());
                chosenDelta = delta;
            }
        }
        return chosen;
    }

    private static boolean prefer(RankPoint end, RankHighlight chosen, boolean rise) {
        if (rise && end.position() != chosen.toRank()) {
            return end.position() < chosen.toRank();
        }
        if (!rise && end.position() != chosen.toRank()) {
            return end.position() > chosen.toRank();
        }
        return end.queryName().compareTo(chosen.queryName()) < 0;
    }
}
