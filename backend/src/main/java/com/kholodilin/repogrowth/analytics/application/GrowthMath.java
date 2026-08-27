package com.kholodilin.repogrowth.analytics.application;

public final class GrowthMath {

    private GrowthMath() {
    }

    /**
     * {@code (current - previous) / previous * 100}, rounded to 1 decimal.
     * {@code null} when the previous value is 0 and current is not (no division by zero).
     */
    public static Double percent(long current, long previous) {
        if (previous == 0) {
            return current == 0 ? 0.0 : null;
        }
        double raw = (current - previous) * 100.0 / previous;
        return Math.round(raw * 10.0) / 10.0;
    }
}
