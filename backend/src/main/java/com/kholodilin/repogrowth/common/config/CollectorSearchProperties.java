package com.kholodilin.repogrowth.common.config;

import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Rank-sort frozen into SEARCH_QUERY / TOPIC_WATCH assignment parameters. Property names stay under
 * {@code collector.search.*} for compatibility with existing deployments. Collector execution
 * settings remain in the standalone collector process.
 */
@ConfigurationProperties("collector.search")
public record CollectorSearchProperties(String rankSort, String rankSortOrder) {
  public CollectorSearchProperties {
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
