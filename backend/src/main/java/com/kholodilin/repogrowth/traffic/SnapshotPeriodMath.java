package com.kholodilin.repogrowth.traffic;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * GitHub referrers/paths APIs return a rolling ~14-day total, not a daily increment.
 * Each stored snapshot is that window ending on the collection day. This splits the
 * snapshot across those 14 days using {@code traffic_daily} as weights, then sums
 * the selected period.
 */
public final class SnapshotPeriodMath {

    public static final int GITHUB_WINDOW_DAYS = 14;

    private SnapshotPeriodMath() {
    }

    public record Observation(
            LocalDate snapshotDate,
            String key,
            String title,
            int views,
            int uniqueVisitors
    ) {
    }

    public record DayTraffic(LocalDate date, int views, int uniqueVisitors) {
    }

    public record AggregatedRow(String key, String title, int views, int uniqueVisitors) {
    }

    public static List<AggregatedRow> aggregate(
            LocalDate fromInclusive,
            LocalDate toInclusive,
            List<Observation> snapshots,
            List<DayTraffic> traffic
    ) {
        Map<LocalDate, List<Observation>> byDate = new HashMap<>();
        TreeSet<LocalDate> snapshotDates = new TreeSet<>();
        for (Observation snapshot : snapshots) {
            snapshotDates.add(snapshot.snapshotDate());
            byDate.computeIfAbsent(snapshot.snapshotDate(), key -> new ArrayList<>()).add(snapshot);
        }
        Map<LocalDate, DayTraffic> trafficByDate = new HashMap<>();
        for (DayTraffic day : traffic) {
            trafficByDate.put(day.date(), day);
        }

        Map<String, double[]> totals = new HashMap<>();
        Map<String, String> titles = new HashMap<>();
        Map<String, LocalDate> titleDates = new HashMap<>();

        for (LocalDate day = fromInclusive; !day.isAfter(toInclusive); day = day.plusDays(1)) {
            LocalDate snapshotDate = coveringSnapshot(day, snapshotDates);
            if (snapshotDate == null) {
                continue;
            }
            LocalDate windowFrom = snapshotDate.minusDays(GITHUB_WINDOW_DAYS - 1);
            int dayViews = viewsOn(trafficByDate, day);
            int dayUniques = uniquesOn(trafficByDate, day);
            int windowViews = sumViews(trafficByDate, windowFrom, snapshotDate);
            int windowUniques = sumUniques(trafficByDate, windowFrom, snapshotDate);
            for (Observation row : byDate.getOrDefault(snapshotDate, List.of())) {
                double views = allocate(row.views(), dayViews, windowViews, day, snapshotDate);
                double uniques = allocateUniques(
                        row.uniqueVisitors(),
                        dayUniques,
                        windowUniques,
                        dayViews,
                        windowViews,
                        day,
                        snapshotDate
                );
                double[] acc = totals.computeIfAbsent(row.key(), key -> new double[2]);
                acc[0] += views;
                acc[1] += uniques;
                LocalDate previousTitleDate = titleDates.get(row.key());
                if (row.title() != null && (previousTitleDate == null || !snapshotDate.isBefore(previousTitleDate))) {
                    titles.put(row.key(), row.title());
                    titleDates.put(row.key(), snapshotDate);
                }
            }
        }

        List<AggregatedRow> rows = new ArrayList<>();
        for (Map.Entry<String, double[]> entry : totals.entrySet()) {
            int views = (int) Math.round(entry.getValue()[0]);
            int uniques = (int) Math.round(entry.getValue()[1]);
            if (views == 0 && uniques == 0) {
                continue;
            }
            rows.add(new AggregatedRow(entry.getKey(), titles.get(entry.getKey()), views, uniques));
        }
        rows.sort(Comparator.comparingInt(AggregatedRow::views).reversed().thenComparing(AggregatedRow::key));
        return rows;
    }

    static LocalDate coveringSnapshot(LocalDate day, TreeSet<LocalDate> snapshotDates) {
        LocalDate latestCovering = snapshotDates.ceiling(day);
        if (latestCovering == null) {
            return null;
        }
        LocalDate windowFrom = latestCovering.minusDays(GITHUB_WINDOW_DAYS - 1);
        if (day.isBefore(windowFrom)) {
            return null;
        }
        return latestCovering;
    }

    private static double allocateUniques(
            int snapshotUniques,
            int dayUniques,
            int windowUniques,
            int dayViews,
            int windowViews,
            LocalDate day,
            LocalDate snapshotDate
    ) {
        if (windowUniques > 0) {
            return snapshotUniques * (double) dayUniques / windowUniques;
        }
        return allocate(snapshotUniques, dayViews, windowViews, day, snapshotDate);
    }

    private static double allocate(
            int snapshotValue,
            int dayWeight,
            int windowWeight,
            LocalDate day,
            LocalDate snapshotDate
    ) {
        if (windowWeight > 0) {
            return snapshotValue * (double) dayWeight / windowWeight;
        }
        LocalDate windowFrom = snapshotDate.minusDays(GITHUB_WINDOW_DAYS - 1);
        if (day.isBefore(windowFrom) || day.isAfter(snapshotDate)) {
            return 0;
        }
        return snapshotValue / (double) GITHUB_WINDOW_DAYS;
    }

    private static int viewsOn(Map<LocalDate, DayTraffic> trafficByDate, LocalDate day) {
        DayTraffic traffic = trafficByDate.get(day);
        return traffic == null ? 0 : traffic.views();
    }

    private static int uniquesOn(Map<LocalDate, DayTraffic> trafficByDate, LocalDate day) {
        DayTraffic traffic = trafficByDate.get(day);
        return traffic == null ? 0 : traffic.uniqueVisitors();
    }

    private static int sumViews(Map<LocalDate, DayTraffic> trafficByDate, LocalDate from, LocalDate to) {
        int sum = 0;
        for (LocalDate cursor = from; !cursor.isAfter(to); cursor = cursor.plusDays(1)) {
            sum += viewsOn(trafficByDate, cursor);
        }
        return sum;
    }

    private static int sumUniques(Map<LocalDate, DayTraffic> trafficByDate, LocalDate from, LocalDate to) {
        int sum = 0;
        for (LocalDate cursor = from; !cursor.isAfter(to); cursor = cursor.plusDays(1)) {
            sum += uniquesOn(trafficByDate, cursor);
        }
        return sum;
    }
}
