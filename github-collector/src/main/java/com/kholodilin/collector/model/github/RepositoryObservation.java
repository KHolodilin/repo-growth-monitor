package com.kholodilin.collector.model.github;

/** Typed repository stats plus opaque metadata JSON for Snapshot.metadata. */
public record RepositoryObservation(long stars, long forks, Long watchers, String rawJson) {}
