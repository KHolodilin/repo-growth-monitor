package com.kholodilin.repogrowth.search.application;

/**
 * Rank movement between two successful search snapshots. Lower position is better.
 */
public final class RankChangeMath {

    private RankChangeMath() {
    }

    public enum Kind {
        NONE,
        UNCHANGED,
        IMPROVED,
        DECLINED,
        ENTERED,
        EXITED
    }

    public record Change(Kind kind, int amount, Integer rank) {
    }

    public static Change between(boolean hasPrevious, Integer previousRank, Integer currentRank, int resultLimit) {
        if (!hasPrevious) {
            return new Change(Kind.NONE, 0, currentRank);
        }
        if (previousRank == null && currentRank != null) {
            return new Change(Kind.ENTERED, 0, currentRank);
        }
        if (previousRank != null && currentRank == null) {
            return new Change(Kind.EXITED, Math.max(0, resultLimit - previousRank), null);
        }
        if (previousRank == null) {
            return new Change(Kind.UNCHANGED, 0, null);
        }
        int delta = previousRank - currentRank;
        if (delta > 0) {
            return new Change(Kind.IMPROVED, delta, currentRank);
        }
        if (delta < 0) {
            return new Change(Kind.DECLINED, -delta, currentRank);
        }
        return new Change(Kind.UNCHANGED, 0, currentRank);
    }
}
