package com.kholodilin.repogrowth.common.config;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CollectorSearchPropertiesTest {
  @Test
  void defaultsMatchBestMatchDescending() {
    CollectorSearchProperties properties = new CollectorSearchProperties(null, null);
    assertThat(properties.rankSort()).isEqualTo("best_match");
    assertThat(properties.rankSortOrder()).isEqualTo("desc");
    assertThat(properties.githubSort()).isNull();
    assertThat(properties.githubOrder()).isNull();
    assertThat(properties.sortModeLabel()).isEqualTo("best_match");
  }

  @Test
  void starsSortKeepsConfiguredOrder() {
    CollectorSearchProperties properties = new CollectorSearchProperties("Stars", "Asc");
    assertThat(properties.rankSort()).isEqualTo("stars");
    assertThat(properties.rankSortOrder()).isEqualTo("asc");
    assertThat(properties.githubSort()).isEqualTo("stars");
    assertThat(properties.githubOrder()).isEqualTo("asc");
  }

  @Test
  void rejectsUnknownSortAndOrder() {
    assertThatThrownBy(() -> new CollectorSearchProperties("forks", "desc"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("rank-sort");
    assertThatThrownBy(() -> new CollectorSearchProperties("stars", "sideways"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("rank-sort-order");
  }
}
