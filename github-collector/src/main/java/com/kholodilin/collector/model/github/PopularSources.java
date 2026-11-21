package com.kholodilin.collector.model.github;

import java.util.List;

public record PopularSources(List<Entry> entries) {
  public PopularSources {
    entries = entries == null ? List.of() : List.copyOf(entries);
  }

  public record Entry(String key, String title, long count, long uniques) {}
}
