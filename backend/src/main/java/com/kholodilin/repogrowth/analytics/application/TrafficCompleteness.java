package com.kholodilin.repogrowth.analytics.application;

import com.kholodilin.repogrowth.collection.domain.CollectionJob;
import com.kholodilin.repogrowth.collection.domain.CollectionJobStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;

/**
 * A stored zero is a real zero. A missing row is not a zero.
 * The collection run is for today. Traffic completeness is for yesterday.
 */
public final class TrafficCompleteness {

    public enum Reason {
        GITHUB_DATA_MISSING,
        COLLECTION_NOT_EXECUTED,
        COLLECTION_FAILED,
        MISSING_DAY
    }

    public enum TrafficJobState {
        ABSENT,
        IN_PROGRESS,
        SUCCEEDED,
        FAILED
    }

    public record Notice(Reason reason, LocalDate date, LocalDate lastTrafficDate) {
    }

    private TrafficCompleteness() {
    }

    public static TrafficJobState jobState(CollectionJob job) {
        if (job == null) {
            return TrafficJobState.ABSENT;
        }
        return jobState(job.status(), job.completedAt(), job.errorCode(), job.errorMessage());
    }

    static TrafficJobState jobState(
            CollectionJobStatus status,
            Instant completedAt,
            String errorCode,
            String errorMessage
    ) {
        if (status == CollectionJobStatus.FAILED) {
            return TrafficJobState.FAILED;
        }
        if (status == CollectionJobStatus.SUCCESS) {
            return TrafficJobState.SUCCEEDED;
        }
        if (completedAt != null && blank(errorCode) && blank(errorMessage)) {
            return TrafficJobState.SUCCEEDED;
        }
        return TrafficJobState.IN_PROGRESS;
    }

    /**
     * Repository-page status. Null means the traffic job is fine, or today's run has not had a chance to finish.
     */
    public static Notice status(LocalDate today, boolean windowOpen, boolean yesterdayStored, TrafficJobState job) {
        if (job == TrafficJobState.FAILED) {
            return new Notice(Reason.COLLECTION_FAILED, today, null);
        }
        if (job == TrafficJobState.ABSENT && !windowOpen) {
            return new Notice(Reason.COLLECTION_NOT_EXECUTED, today, null);
        }
        if (job == TrafficJobState.SUCCEEDED && !yesterdayStored) {
            return new Notice(Reason.GITHUB_DATA_MISSING, today.minusDays(1), null);
        }
        return null;
    }

    /**
     * One portfolio notice for the selected period. A failure or a missed run counts only when today is in the period.
     * A missing stored day counts when that day is in the period and is not today.
     */
    public static Notice portfolio(
            LocalDate today,
            boolean windowOpen,
            LocalDate periodFrom,
            LocalDate periodTo,
            Set<LocalDate> storedInPeriod,
            LocalDate lastStored,
            TrafficJobState job
    ) {
        boolean todayInPeriod = !today.isBefore(periodFrom) && !today.isAfter(periodTo);
        boolean yesterdayStored = lastStored != null && !lastStored.isBefore(today.minusDays(1));
        Notice runNotice = todayInPeriod ? status(today, windowOpen, yesterdayStored, job) : null;
        if (runNotice != null && runNotice.reason() != Reason.GITHUB_DATA_MISSING) {
            return new Notice(runNotice.reason(), runNotice.date(), lastStored);
        }

        LocalDate expectedEnd = periodTo.isBefore(today.minusDays(1)) ? periodTo : today.minusDays(1);
        LocalDate latestMissing = null;
        boolean storedAfter = false;
        if (!periodFrom.isAfter(expectedEnd)) {
            for (LocalDate day = periodFrom; !day.isAfter(expectedEnd); day = day.plusDays(1)) {
                if (storedInPeriod.contains(day)) {
                    if (latestMissing != null) {
                        storedAfter = true;
                    }
                    continue;
                }
                latestMissing = day;
                storedAfter = false;
            }
        }
        if (latestMissing == null || storedAfter || runNotice == null) {
            return null;
        }
        return new Notice(Reason.GITHUB_DATA_MISSING, latestMissing, lastStored);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
