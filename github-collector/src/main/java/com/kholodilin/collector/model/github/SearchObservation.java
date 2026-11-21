package com.kholodilin.collector.model.github;

import java.util.List;

/** Search/topic ranking observation with opaque raw JSON for metadata. */
public record SearchObservation(
    long totalCount,
    boolean incompleteResults,
    List<Hit> items,
    Integer trackedPosition,
    boolean allPublic,
    String rawJson) {
  public SearchObservation {
    items = items == null ? List.of() : List.copyOf(items);
  }

  public record Hit(String id, String fullName, long stars, long forks, boolean isPrivate) {}
}
