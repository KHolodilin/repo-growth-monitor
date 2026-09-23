package com.kholodilin.repogrowth.collection.worker;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class RetryPolicyTest {

    private final RetryPolicy policy = new RetryPolicy();

    @Test
    void exhaustedAfterEightAttempts() {
        assertThat(policy.exhausted(7)).isFalse();
        assertThat(policy.exhausted(8)).isTrue();
    }

    @Test
    void nextAttemptUsesBackoffThenHonorsALaterRateLimitReset() {
        Instant later = Instant.now().plusSeconds(10_000);
        Instant reset = policy.nextAttemptAt(3, later);
        assertThat(reset).isAfterOrEqualTo(later);

        Instant sooner = Instant.now().minusSeconds(60);
        Instant fromBackoff = policy.nextAttemptAt(4, sooner);
        assertThat(fromBackoff).isAfter(Instant.now());
        assertThat(policy.nextAttemptAt(1, null)).isAfter(Instant.now());
        assertThat(policy.nextAttemptAt(2, null)).isAfter(Instant.now());
    }
}
