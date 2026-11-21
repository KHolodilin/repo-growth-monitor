package com.kholodilin.collector.model.github;

import java.util.List;

/**
 * One contributors page and opaque JSON for metadata.
 *
 * <p>{@code hasNext} means the page looks full so another page <em>may</em> exist; it is not a
 * guarantee that more contributors are present.
 */
public record ContributorPage(List<ContributorEntry> items, boolean hasNext, String rawJson) {
  public ContributorPage {
    items = items == null ? List.of() : List.copyOf(items);
  }

  public record ContributorEntry(String login) {}
}
