package com.kholodilin.repogrowth.collection.worker;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class RetryPolicy {

    private static final int MAX_ATTEMPTS = 8;

    public boolean exhausted(int attempt) {
        return attempt >= MAX_ATTEMPTS;
    }

    public Instant nextAttemptAt(int attempt, Instant rateLimitReset) {
        Duration backoff = switch (attempt) {
            case 1 -> Duration.ofMinutes(1);
            case 2 -> Duration.ofMinutes(5);
            case 3 -> Duration.ofMinutes(15);
            default -> Duration.ofMinutes(60);
        };
        long jitterMs = ThreadLocalRandom.current().nextLong(0, 5_000);
        Instant fromBackoff = Instant.now().plus(backoff).plusMillis(jitterMs);
        if (rateLimitReset != null && rateLimitReset.isAfter(fromBackoff)) {
            return rateLimitReset.plusMillis(jitterMs);
        }
        return fromBackoff;
    }
}
