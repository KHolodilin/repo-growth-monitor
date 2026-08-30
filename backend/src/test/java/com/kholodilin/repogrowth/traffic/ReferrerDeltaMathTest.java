package com.kholodilin.repogrowth.traffic;

import com.kholodilin.repogrowth.traffic.ReferrerDeltaMath.Point;
import com.kholodilin.repogrowth.traffic.ReferrerDeltaMath.Result;
import com.kholodilin.repogrowth.traffic.ReferrerDeltaMath.SnapshotRow;
import com.kholodilin.repogrowth.traffic.ReferrerDeltaMath.SourceSeries;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReferrerDeltaMathTest {

    private static final LocalDate AUG_28 = LocalDate.of(2026, 8, 28);
    private static final LocalDate AUG_29 = LocalDate.of(2026, 8, 29);
    private static final LocalDate AUG_30 = LocalDate.of(2026, 8, 30);

    @Test
    void usesDifferenceBetweenConsecutiveSnapshots() {
        Result result = ReferrerDeltaMath.dailyDeltas(List.of(
                new SnapshotRow(AUG_28, "github.com", 215, 4),
                new SnapshotRow(AUG_29, "github.com", 230, 6)
        ), AUG_28, AUG_30);

        assertThat(result.snapshotCount()).isEqualTo(2);
        assertThat(result.sources()).containsExactly(new SourceSeries("github.com", List.of(
                new Point(AUG_29, 15, 2, AUG_28)
        )));
    }

    @Test
    void firstSnapshotWithoutPredecessorIsNotZero() {
        Result result = ReferrerDeltaMath.dailyDeltas(List.of(
                new SnapshotRow(AUG_29, "github.com", 230, 6)
        ), AUG_28, AUG_30);

        assertThat(result.sources()).isEmpty();
        assertThat(result.snapshotCount()).isEqualTo(1);
    }

    @Test
    void newSourceOnLaterSnapshotHasNoPoint() {
        Result result = ReferrerDeltaMath.dailyDeltas(List.of(
                new SnapshotRow(AUG_28, "github.com", 215, 4),
                new SnapshotRow(AUG_29, "github.com", 230, 6),
                new SnapshotRow(AUG_29, "doubao.com", 1, 1)
        ), AUG_28, AUG_30);

        assertThat(result.sources()).extracting(SourceSeries::source).containsExactly("github.com");
    }

    @Test
    void missingSourceIsNotEmittedAsZero() {
        Result result = ReferrerDeltaMath.dailyDeltas(List.of(
                new SnapshotRow(AUG_28, "github.com", 215, 4),
                new SnapshotRow(AUG_28, "Google", 7, 4),
                new SnapshotRow(AUG_29, "github.com", 230, 6)
        ), AUG_28, AUG_30);

        assertThat(result.sources()).extracting(SourceSeries::source).containsExactly("github.com");
    }

    @Test
    void negativeWindowResetIsNoData() {
        Result result = ReferrerDeltaMath.dailyDeltas(List.of(
                new SnapshotRow(AUG_28, "github.com", 230, 6),
                new SnapshotRow(AUG_29, "github.com", 206, 4)
        ), AUG_28, AUG_30);

        assertThat(result.sources()).isEmpty();
        assertThat(result.resets()).hasSize(2);
    }

    @Test
    void doesNotInventAPointOnAMissingCollectionDay() {
        LocalDate aug27 = LocalDate.of(2026, 8, 27);
        Result result = ReferrerDeltaMath.dailyDeltas(List.of(
                new SnapshotRow(aug27, "github.com", 200, 3),
                new SnapshotRow(AUG_29, "github.com", 230, 6)
        ), aug27, AUG_30);

        assertThat(result.sources().getFirst().points()).containsExactly(
                new Point(AUG_29, 30, 3, aug27)
        );
    }

    @Test
    void predecessorBeforeRangeIsUsedOnlyForTheFirstInRangeDelta() {
        LocalDate aug24 = LocalDate.of(2026, 8, 24);
        Result result = ReferrerDeltaMath.dailyDeltas(List.of(
                new SnapshotRow(aug24, "github.com", 100, 2),
                new SnapshotRow(AUG_28, "github.com", 215, 4),
                new SnapshotRow(AUG_29, "github.com", 230, 6)
        ), AUG_28, AUG_30);

        assertThat(result.sources().getFirst().points()).containsExactly(
                new Point(AUG_28, 115, 2, aug24),
                new Point(AUG_29, 15, 2, AUG_28)
        );
    }
}
