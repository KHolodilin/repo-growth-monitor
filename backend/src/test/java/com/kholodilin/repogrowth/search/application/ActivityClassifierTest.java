package com.kholodilin.repogrowth.search.application;

import com.kholodilin.repogrowth.common.config.SearchProperties;
import com.kholodilin.repogrowth.search.domain.ActivityStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class ActivityClassifierTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-08-28T12:00:00Z"), ZoneOffset.UTC);
    private final ActivityClassifier classifier = new ActivityClassifier(
            new SearchProperties(1, 50, Duration.ofHours(24), new SearchProperties.Activity(30, 180)),
            clock
    );

    @Test
    void classifiesActiveLowInactiveAndUnknown() {
        assertThat(classifier.classify(Instant.parse("2026-08-20T00:00:00Z"))).isEqualTo(ActivityStatus.ACTIVE);
        assertThat(classifier.classify(Instant.parse("2026-06-01T00:00:00Z"))).isEqualTo(ActivityStatus.LOW_ACTIVITY);
        assertThat(classifier.classify(Instant.parse("2025-01-01T00:00:00Z"))).isEqualTo(ActivityStatus.INACTIVE);
        assertThat(classifier.classify(null)).isEqualTo(ActivityStatus.UNKNOWN);
        assertThat(classifier.classify(true, Instant.parse("2026-08-20T00:00:00Z"))).isEqualTo(ActivityStatus.ARCHIVED);
    }
}
