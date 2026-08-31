package com.kholodilin.repogrowth.search.application;

import com.kholodilin.repogrowth.search.application.RankChangeMath.Change;
import com.kholodilin.repogrowth.search.application.RankChangeMath.Kind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RankChangeMathTest {

    @Test
    void noPreviousSnapshotIsEmpty() {
        assertThat(RankChangeMath.between(false, null, 2, 50))
                .isEqualTo(new Change(Kind.NONE, 0, 2));
    }

    @Test
    void improvementUsesThePositionDelta() {
        assertThat(RankChangeMath.between(true, 5, 2, 50))
                .isEqualTo(new Change(Kind.IMPROVED, 3, 2));
    }

    @Test
    void declineUsesThePositionDelta() {
        assertThat(RankChangeMath.between(true, 2, 5, 50))
                .isEqualTo(new Change(Kind.DECLINED, 3, 5));
    }

    @Test
    void unchangedRankIsEmpty() {
        assertThat(RankChangeMath.between(true, 5, 5, 50))
                .isEqualTo(new Change(Kind.UNCHANGED, 0, 5));
    }

    @Test
    void enteringTheTrackedRangeIsNew() {
        assertThat(RankChangeMath.between(true, null, 18, 50))
                .isEqualTo(new Change(Kind.ENTERED, 0, 18));
    }

    @Test
    void leavingTheTrackedRangeUsesTheLimitGap() {
        assertThat(RankChangeMath.between(true, 20, null, 50))
                .isEqualTo(new Change(Kind.EXITED, 30, null));
    }

    @Test
    void bothOutOfRangeStayUnchanged() {
        assertThat(RankChangeMath.between(true, null, null, 50))
                .isEqualTo(new Change(Kind.UNCHANGED, 0, null));
    }
}
