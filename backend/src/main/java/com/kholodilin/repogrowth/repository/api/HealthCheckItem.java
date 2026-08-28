package com.kholodilin.repogrowth.repository.api;

public record HealthCheckItem(
        String label,
        boolean passed
) {
}
