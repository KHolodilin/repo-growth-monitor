package com.kholodilin.repogrowth.catalog.model;

/** Accessibility / traffic-permission signals for a tracked repository. */
public record RepositorySignals(boolean accessible, boolean trafficPermissionMissing) {}
