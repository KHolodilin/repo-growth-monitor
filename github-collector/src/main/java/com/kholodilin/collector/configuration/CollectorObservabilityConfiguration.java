package com.kholodilin.collector.configuration;

import com.kholodilin.collector.repository.InMemoryJobQueue;
import com.kholodilin.collector.repository.JobQueue;
import com.kholodilin.collector.service.CollectorJobMetrics;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.health.contributor.*;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "collector.runtime.enabled", havingValue = "true")
public class CollectorObservabilityConfiguration {

  @Bean
  CollectorJobMetrics collectorJobMetrics() {
    return new CollectorJobMetrics();
  }

  @Bean
  MeterBinder collectorQueueMeters(
      JobQueue queue, CapacityProperties capacity, CollectorJobMetrics metrics) {
    return registry -> {
      if (!(queue instanceof InMemoryJobQueue memory)) return;
      registry.gauge("collector.queue.jobs", memory, q -> q.size());
      registry.gauge("collector.queue.bytes", memory, InMemoryJobQueue::totalBytes);
      registry.gauge("collector.queue.in_flight", memory, InMemoryJobQueue::inFlight);
      registry.gauge(
          "collector.queue.utilization.jobs", memory, q -> (double) q.size() / capacity.maxJobs());
      FunctionCounter.builder("collector.jobs.accepted", metrics, m -> (double) m.accepted())
          .register(registry);
      FunctionCounter.builder(
              "collector.jobs.rejected_capacity", metrics, m -> (double) m.rejectedCapacity())
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
