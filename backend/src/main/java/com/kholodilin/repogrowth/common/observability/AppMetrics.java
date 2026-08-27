package com.kholodilin.repogrowth.common.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

@Component
public class AppMetrics {

    private final MeterRegistry registry;
    private final AtomicInteger trackedRepositories = new AtomicInteger(0);

    public AppMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("repositories.tracked", trackedRepositories, AtomicInteger::get)
                .description("Number of tracked repositories")
                .register(registry);
    }

    public void setTrackedRepositories(int count) {
        trackedRepositories.set(count);
    }

    public void githubRequest(String operation, boolean success) {
        Counter.builder("github.api.requests")
                .tag("operation", operation)
                .register(registry)
                .increment();
        if (!success) {
            Counter.builder("github.api.errors")
                    .tag("operation", operation)
                    .register(registry)
                    .increment();
        }
    }

    public Timer.Sample startTimer() {
        return Timer.start(registry);
    }

    public void recordDuration(Timer.Sample sample, String metric) {
        sample.stop(Timer.builder(metric).register(registry));
    }

    public void collectionJobs(String status, Supplier<Number> supplier) {
        Gauge.builder("collection.jobs." + status.toLowerCase(), supplier)
                .register(registry);
    }
}
