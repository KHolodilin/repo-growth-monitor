package com.kholodilin.repogrowth.search.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SearchSortModeTest {

    @Test
    void blankSortModeMatchesDatabaseDefaultStars() {
        assertThat(SearchQueryService.normalizeSortMode(null)).isEqualTo("stars");
        assertThat(SearchQueryService.normalizeSortMode("")).isEqualTo("stars");
        assertThat(SearchQueryService.normalizeSortMode("best_match")).isEqualTo("best_match");
        assertThat(SearchQueryService.normalizeSortMode("stars")).isEqualTo("stars");
    }
}
