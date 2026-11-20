package com.kholodilin.collector;

import com.kholodilin.collector.queue.InMemoryJobQueue;
import com.kholodilin.collector.queue.JobQueue;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.*;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "collector.runtime.enabled", havingValue = "true")
public class CollectorObservabilityConfiguration {

  public static final AtomicLong ACCEPTED = new AtomicLong();
  public static final AtomicLong REJECTED_CAPACITY = new AtomicLong();

  @Bean
  MeterBinder collectorQueueMeters(JobQueue queue, CapacityProperties capacity) {
    return registry -> {
      if (!(queue instanceof InMemoryJobQueue memory)) return;
      registry.gauge("collector.queue.jobs", memory, q -> q.size());
      registry.gauge("collector.queue.bytes", memory, InMemoryJobQueue::totalBytes);
      registry.gauge("collector.queue.in_flight", memory, InMemoryJobQueue::inFlight);
      registry.gauge(
          "collector.queue.utilization.jobs", memory, q -> (double) q.size() / capacity.maxJobs());
      FunctionCounter.builder("collector.jobs.accepted", ACCEPTED, AtomicLong::doubleValue)
          .register(registry);
      FunctionCounter.builder(
              "collector.jobs.rejected_capacity", REJECTED_CAPACITY, AtomicLong::doubleValue)
          .register(registry);
    };
  }

  @Bean
  HealthIndicator collectorReady(JobQueue queue) {
    return () ->
        Health.up()
            .withDetail("runtime", "memory")
            .withDetail("queueSize", queue instanceof InMemoryJobQueue m ? m.size() : -1)
            .build();
  }
}
