package com.kholodilin.repogrowth.analytics.application;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Set;

public record DashboardPeriod(
        String value,
        LocalDate from,
        LocalDate to,
        LocalDate previousFrom,
        LocalDate previousTo
) {

    private static final Set<String> ALLOWED = Set.of("1d", "7d", "30d", "90d", "1y", "all");

    public static DashboardPeriod of(String raw, LocalDate today, LocalDate earliestTrafficDate) {
        String period = normalize(raw);
        LocalDate from;
        if ("all".equals(period)) {
            from = earliestTrafficDate != null ? earliestTrafficDate : today;
            return new DashboardPeriod(period, from, today, null, null);
        }
        // GitHub publishes completed UTC days; 1d is yesterday, not the unfinished today.
        from = switch (period) {
            case "1d" -> today.minusDays(1);
            case "7d" -> today.minusDays(6);
            case "90d" -> today.minusDays(89);
            case "1y" -> today.minusYears(1).plusDays(1);
            default -> today.minusDays(29);
        };
        LocalDate toInclusive = "1d".equals(period) ? from : today;
        long days = ChronoUnit.DAYS.between(from, toInclusive) + 1;
        LocalDate previousTo = from.minusDays(1);
        LocalDate previousFrom = previousTo.minusDays(days - 1);
        return new DashboardPeriod(period, from, toInclusive, previousFrom, previousTo);
    }

    public boolean allTime() {
        return "all".equals(value);
    }

    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "30d";
        }
        String period = raw.trim().toLowerCase(Locale.ROOT);
        return ALLOWED.contains(period) ? period : "30d";
    }
}
