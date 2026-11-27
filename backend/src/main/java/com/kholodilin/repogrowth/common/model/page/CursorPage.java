package com.kholodilin.repogrowth.common.model.page;

import java.util.List;

/**
 * Opaque cursor page for resource list endpoints. {@code nextCursor} is client-opaque; callers must
 * not assume it is a raw id.
 */
public record CursorPage<T>(List<T> items, String nextCursor) {

  public CursorPage {
    items = List.copyOf(items);
  }

  public static <T> CursorPage<T> of(List<T> items, String nextCursor) {
    return new CursorPage<>(items, nextCursor);
  }

  public static <T> CursorPage<T> empty() {
    return new CursorPage<>(List.of(), null);
  }
}
