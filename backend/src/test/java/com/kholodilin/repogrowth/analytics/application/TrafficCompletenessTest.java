package com.kholodilin.repogrowth.analytics.application;

import com.kholodilin.repogrowth.analytics.application.TrafficCompleteness.Notice;
import com.kholodilin.repogrowth.analytics.application.TrafficCompleteness.Reason;
import com.kholodilin.repogrowth.analytics.application.TrafficCompleteness.TrafficJobState;
import com.kholodilin.repogrowth.collection.domain.CollectionJob;
import com.kholodilin.repogrowth.collection.domain.CollectionJobStatus;
import com.kholodilin.repogrowth.collection.domain.CollectionJobType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class TrafficCompletenessTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 19);
    private static final LocalDate YESTERDAY = LocalDate.of(2026, 10, 18);

    @Test
    void succeededJobWithNoYesterdayRowIsMissingGitHubData() {
        Notice notice = TrafficCompleteness.status(TODAY, true, false, TrafficJobState.SUCCEEDED);
        assertThat(notice).isEqualTo(new Notice(Reason.GITHUB_DATA_MISSING, YESTERDAY, null));
    }

    @Test
    void retryAfterSuccessDoesNotClearTheMissingDay() {
        CollectionJob retry = job(CollectionJobStatus.RUNNING, Instant.parse("2026-10-19T12:00:00Z"), null, null);
        assertThat(TrafficCompleteness.jobState(retry)).isEqualTo(TrafficJobState.SUCCEEDED);
        assertThat(TrafficCompleteness.status(TODAY, true, false, TrafficCompleteness.jobState(retry)).reason())
                .isEqualTo(Reason.GITHUB_DATA_MISSING);
    }

    @Test
    void storedYesterdayIsOkEvenWhenTheValueIsZero() {
        assertThat(TrafficCompleteness.status(TODAY, true, true, TrafficJobState.SUCCEEDED)).isNull();
    }

    @Test
    void openWindowWithoutARunIsNotAMiss() {
        assertThat(TrafficCompleteness.status(TODAY, true, false, TrafficJobState.ABSENT)).isNull();
        assertThat(TrafficCompleteness.status(TODAY, true, false, TrafficJobState.IN_PROGRESS)).isNull();
    }

    @Test
    void closedWindowWithoutARunWasNotExecuted() {
        Notice notice = TrafficCompleteness.status(TODAY, false, true, TrafficJobState.ABSENT);
        assertThat(notice).isEqualTo(new Notice(Reason.COLLECTION_NOT_EXECUTED, TODAY, null));
    }

    @Test
    void failedJobStaysFailedWhileYesterdayIsPresent() {
        Notice notice = TrafficCompleteness.status(TODAY, true, true, TrafficJobState.FAILED);
        assertThat(notice).isEqualTo(new Notice(Reason.COLLECTION_FAILED, TODAY, null));
        CollectionJob failed = job(CollectionJobStatus.FAILED, Instant.parse("2026-10-19T12:00:00Z"), "GITHUB_API_ERROR", "boom");
        assertThat(TrafficCompleteness.jobState(failed)).isEqualTo(TrafficJobState.FAILED);
    }

    @Test
    void requeuedFailureIsStillInProgress() {
        CollectionJob requeued = job(CollectionJobStatus.READY, Instant.parse("2026-10-19T12:00:00Z"), "GITHUB_API_ERROR", "boom");
        assertThat(TrafficCompleteness.jobState(requeued)).isEqualTo(TrafficJobState.IN_PROGRESS);
        assertThat(TrafficCompleteness.jobState(null)).isEqualTo(TrafficJobState.ABSENT);
    }

    @Test
    void portfolioCountsATrailingGapAndAHoleSeparatelyFromAFailure() {
        Notice trailing = TrafficCompleteness.portfolio(
                TODAY, true, TODAY.minusDays(6), TODAY,
                Set.of(LocalDate.of(2026, 10, 17)),
                LocalDate.of(2026, 10, 17),
                TrafficJobState.SUCCEEDED
        );
        assertThat(trailing).isEqualTo(new Notice(Reason.GITHUB_DATA_MISSING, YESTERDAY, LocalDate.of(2026, 10, 17)));

        Notice hole = TrafficCompleteness.portfolio(
                TODAY, true, TODAY.minusDays(6), TODAY,
                Set.of(LocalDate.of(2026, 10, 13), LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 16), LocalDate.of(2026, 10, 17), YESTERDAY),
                YESTERDAY,
                TrafficJobState.SUCCEEDED
        );
        assertThat(hole).isEqualTo(new Notice(Reason.MISSING_DAY, LocalDate.of(2026, 10, 15), null));

        Notice failed = TrafficCompleteness.portfolio(
                TODAY, true, TODAY.minusDays(6), TODAY,
                Set.of(YESTERDAY),
                YESTERDAY,
                TrafficJobState.FAILED
        );
        assertThat(failed.reason()).isEqualTo(Reason.COLLECTION_FAILED);
        assertThat(failed.date()).isEqualTo(TODAY);
    }

    @Test
    void periodThatExcludesTheMissingDayDropsTheNotice() {
        Notice notice = TrafficCompleteness.portfolio(
                TODAY, false, YESTERDAY, YESTERDAY,
                Set.of(YESTERDAY),
                YESTERDAY,
                TrafficJobState.ABSENT
        );
        assertThat(notice).isNull();
    }

    @Test
    void closedWindowOutsideTheOneDayPeriodDoesNotReportToday() {
        Notice notice = TrafficCompleteness.portfolio(
                TODAY, false, YESTERDAY, YESTERDAY,
                Set.of(),
                LocalDate.of(2026, 10, 17),
                TrafficJobState.ABSENT
        );
        assertThat(notice.reason()).isEqualTo(Reason.MISSING_DAY);
        assertThat(notice.date()).isEqualTo(YESTERDAY);
    }

    private static CollectionJob job(CollectionJobStatus status, Instant completedAt, String errorCode, String errorMessage) {
        return new CollectionJob(
                1L, 2L, 3L, CollectionJobType.TRAFFIC, TODAY,
                status, 2, null, null, null, null, completedAt, errorCode, errorMessage
        );
    }
}
