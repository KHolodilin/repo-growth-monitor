package com.kholodilin.repogrowth.traffic;

import com.kholodilin.repogrowth.traffic.SnapshotHistoryMath.Cell;
import com.kholodilin.repogrowth.traffic.SnapshotHistoryMath.Observation;
import com.kholodilin.repogrowth.traffic.SnapshotHistoryMath.Result;
import com.kholodilin.repogrowth.traffic.SnapshotHistoryMath.Row;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SnapshotHistoryMathTest {

    private static final LocalDate AUG_28 = LocalDate.of(2026, 8, 28);
    private static final LocalDate AUG_29 = LocalDate.of(2026, 8, 29);
    private static final LocalDate AUG_30 = LocalDate.of(2026, 8, 30);

    @Test
    void keepsSnapshotValuesAndSignedDeltas() {
        Result result = SnapshotHistoryMath.pivot(List.of(
                new Observation(AUG_28, "github.com", null, 217, 6),
                new Observation(AUG_29, "github.com", null, 243, 7)
        ), AUG_28, AUG_30);

        assertThat(result.dates()).containsExactly(AUG_28, AUG_29);
        assertThat(result.rows()).containsExactly(new Row("github.com", null, List.of(
                new Cell(AUG_28, 6, 217, null, null, false),
                new Cell(AUG_29, 7, 243, 1, 26, false)
        )));
    }

    @Test
    void negativeWindowResetKeepsTheSign() {
        Result result = SnapshotHistoryMath.pivot(List.of(
                new Observation(AUG_28, "github.com", null, 217, 6),
                new Observation(AUG_29, "github.com", null, 206, 6)
        ), AUG_28, AUG_30);

        assertThat(result.rows().getFirst().cells().get(1)).isEqualTo(
                new Cell(AUG_29, 6, 206, 0, -11, false)
        );
    }

    @Test
    void firstSnapshotInDataHasNoDelta() {
        Result result = SnapshotHistoryMath.pivot(List.of(
                new Observation(AUG_29, "github.com", null, 243, 7)
        ), AUG_28, AUG_30);

        assertThat(result.rows().getFirst().cells()).containsExactly(
                new Cell(AUG_29, 7, 243, null, null, false)
        );
    }

    @Test
    void keyMissingFromThePreviousSnapshotIsFirstSeen() {
        Result result = SnapshotHistoryMath.pivot(List.of(
                new Observation(AUG_28, "github.com", null, 217, 6),
                new Observation(AUG_29, "github.com", null, 243, 7),
                new Observation(AUG_29, "doubao.com", null, 1, 1)
        ), AUG_28, AUG_30);

        assertThat(result.rows()).extracting(Row::key).containsExactly("github.com", "doubao.com");
        assertThat(result.rows().getLast().cells()).containsExactly(
                new Cell(AUG_28, null, null, null, null, false),
                new Cell(AUG_29, 1, 1, null, null, true)
        );
    }

    @Test
    void missingCollectionDayDoesNotBecomeAColumn() {
        Result result = SnapshotHistoryMath.pivot(List.of(
                new Observation(AUG_28, "github.com", null, 217, 6),
                new Observation(AUG_30, "github.com", null, 243, 7)
        ), AUG_28, AUG_30);

        assertThat(result.dates()).containsExactly(AUG_28, AUG_30);
        assertThat(result.rows().getFirst().cells().getLast()).isEqualTo(
                new Cell(AUG_30, 7, 243, 1, 26, false)
        );
    }

    @Test
    void predecessorBeforeRangeFeedsTheLeftmostDeltaWithoutBecomingAColumn() {
        LocalDate aug24 = LocalDate.of(2026, 8, 24);
        Result result = SnapshotHistoryMath.pivot(List.of(
                new Observation(aug24, "github.com", null, 200, 3),
                new Observation(AUG_28, "github.com", null, 217, 6)
        ), AUG_28, AUG_30);

        assertThat(result.dates()).containsExactly(AUG_28);
        assertThat(result.rows().getFirst().cells()).containsExactly(
                new Cell(AUG_28, 6, 217, 3, 17, false)
        );
    }

    @Test
    void ordersRowsByTheMostRecentSnapshotThatContainsTheKey() {
        Result result = SnapshotHistoryMath.pivot(List.of(
                new Observation(AUG_28, "Google", null, 7, 4),
                new Observation(AUG_28, "github.com", null, 217, 6),
                new Observation(AUG_29, "github.com", null, 243, 7)
        ), AUG_28, AUG_30);

        assertThat(result.rows()).extracting(Row::key).containsExactly("github.com", "Google");
    }

    @Test
    void keepsTheLatestTitleForPaths() {
        Result result = SnapshotHistoryMath.pivot(List.of(
                new Observation(AUG_28, "/owner/repo", "Old title", 10, 2),
                new Observation(AUG_29, "/owner/repo", "New title", 12, 3)
        ), AUG_28, AUG_30);

        assertThat(result.rows().getFirst().title()).isEqualTo("New title");
    }

    @Test
    void returnsNothingWhenNoSnapshotFallsInTheRange() {
        LocalDate aug24 = LocalDate.of(2026, 8, 24);
        Result result = SnapshotHistoryMath.pivot(List.of(
                new Observation(aug24, "github.com", null, 200, 3)
        ), AUG_28, AUG_30);

        assertThat(result.dates()).isEmpty();
        assertThat(result.rows()).isEmpty();
    }
}
