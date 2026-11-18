package com.kholodilin.repogrowth.search.application;

import com.kholodilin.repogrowth.search.domain.SearchRun;
import com.kholodilin.repogrowth.search.domain.SearchRunStatus;
import java.time.LocalDate;
import java.util.List;
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

    @Test
    void chartKeepsStarsWhenBestMatchTipArrives() {
        SearchRun stars = run("stars");
        SearchRun tip = run("best_match");
        assertThat(SearchQueryService.chartSortMode(List.of(stars, tip), tip)).isEqualTo("stars");
        assertThat(SearchQueryService.chartSortMode(List.of(tip), tip)).isEqualTo("best_match");
    }

    @Test
    void datedSearchTargetKeysKeepQueryIdParseable() {
        assertThat(com.kholodilin.repogrowth.orchestration.CollectionRequestService.targetId("query:12")).isEqualTo(12L);
        assertThat(com.kholodilin.repogrowth.orchestration.CollectionRequestService.targetId("query:12@2026-11-17")).isEqualTo(12L);
        assertThat(com.kholodilin.repogrowth.orchestration.CollectionRequestService.searchTargetKey(
                12L, LocalDate.parse("2026-11-17"), LocalDate.parse("2026-11-18")))
                .isEqualTo("query:12@2026-11-17");
        assertThat(com.kholodilin.repogrowth.orchestration.CollectionRequestService.searchTargetKey(
                12L, LocalDate.parse("2026-11-18"), LocalDate.parse("2026-11-18")))
                .isEqualTo("query:12");
    }

    private static SearchRun run(String sortMode) {
        return new SearchRun(
                1L, 1L, 1L, LocalDate.parse("2026-11-18"), SearchRunStatus.SUCCESS,
                1, null, null, null, null, null, null, 10, 1, null, sortMode, null, null
        );
    }
}
