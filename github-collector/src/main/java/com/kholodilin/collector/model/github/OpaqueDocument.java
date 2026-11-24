package com.kholodilin.collector.model.github;

/** Optional GitHub document retained as opaque JSON when present. */
public record OpaqueDocument(boolean present, String rawJson, String name) {
  public static OpaqueDocument absent() {
    return new OpaqueDocument(false, null, null);
  }

  public static OpaqueDocument of(String rawJson, String name) {
    return new OpaqueDocument(true, rawJson, name);
  }
}
