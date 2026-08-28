package com.kholodilin.repogrowth.repository.api;

import java.util.List;

public record RepositoryHealthResponse(
        List<HealthCheckItem> discoverability,
        List<HealthCheckItem> communityStandards
) {
}
