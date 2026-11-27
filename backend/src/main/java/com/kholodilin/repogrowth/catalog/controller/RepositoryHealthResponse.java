package com.kholodilin.repogrowth.catalog.controller;

import java.util.List;

public record RepositoryHealthResponse(
    List<HealthCheckItem> discoverability, List<HealthCheckItem> communityStandards) {}
