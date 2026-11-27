package com.kholodilin.repogrowth.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.kholodilin.repogrowth.catalog.repository.RepositoryJdbcRepository.RepositorySignals;
import com.kholodilin.repogrowth.collection.model.CollectionJob;
import com.kholodilin.repogrowth.collection.model.CollectionJobStatus;
import com.kholodilin.repogrowth.collection.model.CollectionJobType;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class PortfolioDashboardSignalsTest {

  @Test
  void readsMissingAndPresentAccessFlags() {
    assertThat(PortfolioDashboardService.accessible(null)).isTrue();
    assertThat(PortfolioDashboardService.accessible(new RepositorySignals(false, true))).isFalse();
    assertThat(PortfolioDashboardService.accessible(new RepositorySignals(true, false))).isTrue();
    assertThat(PortfolioDashboardService.trafficMissing(null)).isFalse();
    assertThat(PortfolioDashboardService.trafficMissing(new RepositorySignals(true, true))).isTrue();
    assertThat(PortfolioDashboardService.trafficMissing(new RepositorySignals(true, false))).isFalse();
  }

  @Test
  void copiesTheJobErrorIntoTheDashboardStatus() {
    var status =
        PortfolioDashboardService.jobStatus(
            new CollectionJob(
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
                "rate limit"));
    assertThat(status.jobType()).isEqualTo("REFERRERS");
    assertThat(status.status()).isEqualTo("FAILED");
    assertThat(status.errorMessage()).isEqualTo("rate limit");
  }
}
