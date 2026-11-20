package com.kholodilin.collector.search;

import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Search Rank collection sort for this node (and RGM when it freezes Parameters). Default {@code
 * best_match} matches GitHub's default ranking.
 */
@ConfigurationProperties("collector.search")
public record SearchProperties(String rankSort, String rankSortOrder) {
  public SearchProperties {
    rankSort =
        rankSort == null || rankSort.isBlank()
            ? "best_match"
            : rankSort.trim().toLowerCase(Locale.ROOT);
    rankSortOrder =
        rankSortOrder == null || rankSortOrder.isBlank()
            ? "desc"
            : rankSortOrder.trim().toLowerCase(Locale.ROOT);
    if (!Set.of("best_match", "stars").contains(rankSort))
      throw new IllegalArgumentException("collector.search.rank-sort must be best_match or stars");
    if (!Set.of("asc", "desc").contains(rankSortOrder))
      throw new IllegalArgumentException("collector.search.rank-sort-order must be asc or desc");
  }

  /** Null sort/order means GitHub Best Match (omit sort query params). */
  public String githubSort() {
    return "best_match".equals(rankSort) ? null : rankSort;
  }

  public String githubOrder() {
    return githubSort() == null ? null : rankSortOrder;
  }

  public String sortModeLabel() {
    return rankSort;
  }
}
