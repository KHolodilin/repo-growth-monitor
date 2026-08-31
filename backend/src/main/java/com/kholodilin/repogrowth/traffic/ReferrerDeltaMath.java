package com.kholodilin.repogrowth.traffic;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * GitHub referrers are rolling window snapshots. The chart uses the day-to-day
 * change between consecutive stored snapshots. A source that first appears is a
 * delta from 0; missing collection days are not filled with 0.
 */
public final class ReferrerDeltaMath {

    public static final String OTHER = "Other";

    private ReferrerDeltaMath() {
    }

    public record SnapshotRow(LocalDate snapshotDate, String source, int views, int uniqueVisitors) {
    }

    public record Point(LocalDate date, Integer views, Integer visitors, LocalDate previousSnapshotDate) {
    }

    public record SourceSeries(String source, List<Point> points) {
    }

    public record NegativeReset(LocalDate date, String source, String metric, int current, int previous) {
    }

    public record Result(List<SourceSeries> sources, List<NegativeReset> resets, int snapshotCount) {
    }

    public static Result dailyDeltas(List<SnapshotRow> rows, LocalDate fromInclusive, LocalDate toInclusive) {
        TreeMap<LocalDate, Map<String, SnapshotRow>> byDate = new TreeMap<>();
        for (SnapshotRow row : rows) {
            byDate.computeIfAbsent(row.snapshotDate(), key -> new LinkedHashMap<>()).put(row.source(), row);
        }
        List<LocalDate> dates = new ArrayList<>(byDate.keySet());
        Map<String, List<Point>> series = new LinkedHashMap<>();
        List<NegativeReset> resets = new ArrayList<>();

        for (int index = 1; index < dates.size(); index++) {
            LocalDate previousDate = dates.get(index - 1);
            LocalDate currentDate = dates.get(index);
            if (currentDate.isBefore(fromInclusive) || currentDate.isAfter(toInclusive)) {
                continue;
            }
            Map<String, SnapshotRow> previous = byDate.get(previousDate);
            Map<String, SnapshotRow> current = byDate.get(currentDate);
            for (SnapshotRow row : current.values()) {
                SnapshotRow prior = previous.get(row.source());
                int previousViews = prior == null ? 0 : prior.views();
                int previousVisitors = prior == null ? 0 : prior.uniqueVisitors();
                Integer views = delta(row.views(), previousViews);
                Integer visitors = delta(row.uniqueVisitors(), previousVisitors);
                if (views == null && row.views() < previousViews) {
                    resets.add(new NegativeReset(currentDate, row.source(), "views", row.views(), previousViews));
                }
                if (visitors == null && row.uniqueVisitors() < previousVisitors) {
                    resets.add(new NegativeReset(currentDate, row.source(), "visitors", row.uniqueVisitors(), previousVisitors));
                }
                if (views == null && visitors == null) {
                    continue;
                }
                series.computeIfAbsent(row.source(), key -> new ArrayList<>())
                        .add(new Point(currentDate, views, visitors, previousDate));
            }
        }

        List<SourceSeries> sources = new ArrayList<>();
        for (Map.Entry<String, List<Point>> entry : series.entrySet()) {
            sources.add(new SourceSeries(entry.getKey(), List.copyOf(entry.getValue())));
        }
        return new Result(List.copyOf(sources), List.copyOf(resets), dates.size());
    }

    public static int sumViews(List<Point> points) {
        int total = 0;
        for (Point point : points) {
            if (point.views() != null) {
                total += point.views();
            }
        }
        return total;
    }

    public static int sumVisitors(List<Point> points) {
        int total = 0;
        for (Point point : points) {
            if (point.visitors() != null) {
                total += point.visitors();
            }
        }
        return total;
    }

    private static Integer delta(int current, int previous) {
        int value = current - previous;
        return value < 0 ? null : value;
    }
}
