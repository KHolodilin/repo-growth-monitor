package com.kholodilin.repogrowth.search.domain;

public enum SearchRunStatus {
    READY,
    RUNNING,
    RETRY,
    SUCCESS,
    FAILED,
    /**
     * A day that ended without the planner ever queuing the query. GitHub Search only returns the
     * current ranking, so the day cannot be filled in later; the row exists to tell a gap in the
     * collection apart from a repository that fell out of the results.
     */
    MISSED
}
