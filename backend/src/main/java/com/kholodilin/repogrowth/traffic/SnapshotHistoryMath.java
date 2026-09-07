package com.kholodilin.repogrowth.traffic;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * GitHub referrers and paths are rolling window snapshots. The history table shows every snapshot
 * value as GitHub reported it plus the signed change from the previous snapshot. A key missing from
 * the previous snapshot is reported as first seen instead of a delta from zero, and dates without a
 * snapshot stay empty instead of being filled with zeros. A snapshot stored before the requested
 * range is used only to compute the delta of the leftmost column and never becomes a column itself.
 */
public final class SnapshotHistoryMath {

    private SnapshotHistoryMath() {
    }

    public record Observation(
            LocalDate snapshotDate,
            String key,
            String title,
            int views,
            int uniqueVisitors
    ) {
    }

    public record Cell(
            LocalDate date,
            Integer visitors,
            Integer views,
            Integer visitorsDelta,
            Integer viewsDelta,
            boolean firstSeen
    ) {
    }

    public record Row(String key, String title, List<Cell> cells) {
    }

    public record Result(List<LocalDate> dates, List<Row> rows) {
    }

    public static Result pivot(List<Observation> snapshots, LocalDate fromInclusive, LocalDate toInclusive) {
        TreeMap<LocalDate, Map<String, Observation>> byDate = new TreeMap<>();
        for (Observation snapshot : snapshots) {
            byDate.computeIfAbsent(snapshot.snapshotDate(), date -> new LinkedHashMap<>())
                    .put(snapshot.key(), snapshot);
        }
        List<LocalDate> dates = byDate.keySet().stream()
                .filter(date -> !date.isBefore(fromInclusive) && !date.isAfter(toInclusive))
                .toList();
        if (dates.isEmpty()) {
            return new Result(List.of(), List.of());
        }

        Set<String> keys = new LinkedHashSet<>();
        for (LocalDate date : dates) {
            keys.addAll(byDate.get(date).keySet());
        }
        Map<String, String> titles = latestTitles(byDate, dates);

        List<Row> rows = new ArrayList<>();
        for (String key : keys) {
            List<Cell> cells = new ArrayList<>(dates.size());
            for (LocalDate date : dates) {
                cells.add(cell(byDate, date, key));
            }
            rows.add(new Row(key, titles.get(key), List.copyOf(cells)));
        }
        rows.sort(byLatestValue());
        return new Result(dates, List.copyOf(rows));
    }

    private static Cell cell(TreeMap<LocalDate, Map<String, Observation>> byDate, LocalDate date, String key) {
        Observation current = byDate.get(date).get(key);
        if (current == null) {
            return new Cell(date, null, null, null, null, false);
        }
        LocalDate previousDate = byDate.lowerKey(date);
        if (previousDate == null) {
            return new Cell(date, current.uniqueVisitors(), current.views(), null, null, false);
        }
        Observation previous = byDate.get(previousDate).get(key);
        if (previous == null) {
            return new Cell(date, current.uniqueVisitors(), current.views(), null, null, true);
        }
        return new Cell(
                date,
                current.uniqueVisitors(),
                current.views(),
                current.uniqueVisitors() - previous.uniqueVisitors(),
                current.views() - previous.views(),
                false
        );
    }

    private static Map<String, String> latestTitles(
            TreeMap<LocalDate, Map<String, Observation>> byDate,
            List<LocalDate> dates
    ) {
        Map<String, String> titles = new HashMap<>();
        for (LocalDate date : dates) {
            for (Observation snapshot : byDate.get(date).values()) {
                if (snapshot.title() != null) {
                    titles.put(snapshot.key(), snapshot.title());
                }
            }
        }
        return titles;
    }

    /**
     * Rows are ordered like the compact card: by the most recent snapshot that actually contains the
     * key, so a source that dropped out of the newest snapshot keeps its place instead of sinking to
     * the bottom as a zero.
     */
    private static Comparator<Row> byLatestValue() {
        return Comparator.comparingInt((Row row) -> latest(row, true)).reversed()
                .thenComparing(Comparator.comparingInt((Row row) -> latest(row, false)).reversed())
                .thenComparing(Row::key);
    }

    private static int latest(Row row, boolean visitors) {
        for (int index = row.cells().size() - 1; index >= 0; index--) {
            Cell cell = row.cells().get(index);
            Integer value = visitors ? cell.visitors() : cell.views();
            if (value != null) {
                return value;
            }
        }
        return 0;
    }
}
