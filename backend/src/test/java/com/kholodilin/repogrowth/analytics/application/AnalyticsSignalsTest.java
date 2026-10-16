package com.kholodilin.repogrowth.analytics.application;

import com.kholodilin.repogrowth.collection.domain.CollectionJob;
import com.kholodilin.repogrowth.collection.domain.CollectionJobStatus;
import com.kholodilin.repogrowth.collection.domain.CollectionJobType;
import com.kholodilin.repogrowth.repository.persistence.RepositoryJdbcRepository.RepositorySignals;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class AnalyticsSignalsTest {

    @Test
    void readsMissingAndPresentAccessFlags() {
        assertThat(AnalyticsService.accessible(null)).isTrue();
        assertThat(AnalyticsService.accessible(new RepositorySignals(false, true))).isFalse();
        assertThat(AnalyticsService.accessible(new RepositorySignals(true, false))).isTrue();
        assertThat(AnalyticsService.trafficMissing(null)).isFalse();
        assertThat(AnalyticsService.trafficMissing(new RepositorySignals(true, true))).isTrue();
        assertThat(AnalyticsService.trafficMissing(new RepositorySignals(true, false))).isFalse();
    }

    @Test
    void copiesTheJobErrorIntoTheDashboardStatus() {
        var status = AnalyticsService.jobStatus(new CollectionJob(
                1L,
                2L,
                13L,
                CollectionJobType.REFERRERS,
                LocalDate.parse("2026-09-23"),
                CollectionJobStatus.FAILED,
                1,
                null,
                null,
                null,
                null,
                null,
                "GITHUB_API_ERROR",
                "rate limit"
        ));
        assertThat(status.jobType()).isEqualTo("REFERRERS");
        assertThat(status.status()).isEqualTo("FAILED");
        assertThat(status.errorMessage()).isEqualTo("rate limit");
    }
}
