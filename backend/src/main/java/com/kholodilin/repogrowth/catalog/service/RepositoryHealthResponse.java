package com.kholodilin.repogrowth.catalog.service;

import java.util.List;

public record RepositoryHealthResponse(
    List<HealthCheckItem> discoverability, List<HealthCheckItem> communityStandards) {}
