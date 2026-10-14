package com.kholodilin.repogrowth.common;

import com.kholodilin.repogrowth.common.api.ApiException;
import com.kholodilin.repogrowth.common.api.ErrorCode;
import com.kholodilin.repogrowth.common.config.SearchProperties;
import com.kholodilin.repogrowth.common.observability.AppMetrics;
import com.kholodilin.repogrowth.event.detect.ReadmeSignificance;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class CoverageEdgesTest {

    @Test
    void defaultsAndMetricsAndReadmeEdges() {
        SearchProperties properties = new SearchProperties(1, 50, null, 0, null);
        assertThat(properties.enrichmentTtl()).isEqualTo(Duration.ofHours(24));
        assertThat(properties.gapLookbackDays()).isEqualTo(30);
        assertThat(properties.activity().activeDays()).isEqualTo(30);
        assertThat(new SearchProperties.Activity(0, 0).lowActivityDays()).isEqualTo(180);

        AppMetrics metrics = new AppMetrics(new SimpleMeterRegistry());
        metrics.recordDuration(metrics.startTimer(), "edge.duration");
        metrics.collectionJobs("ready", () -> 1);

        ApiException caused = new ApiException(ErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR, "boom", new IllegalStateException("cause"));
        assertThat(caused.code()).isEqualTo(ErrorCode.INTERNAL_ERROR);
        assertThat(caused.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        assertThat(ReadmeSignificance.normalize("  ")).isEmpty();
        assertThat(ReadmeSignificance.significant("", "x".repeat(400))).isTrue();
    }
}
