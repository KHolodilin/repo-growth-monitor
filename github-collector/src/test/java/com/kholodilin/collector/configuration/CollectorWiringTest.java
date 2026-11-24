package com.kholodilin.collector.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import com.kholodilin.collector.repository.JobQueue;
import com.kholodilin.collector.service.CollectorAcceptanceService;
import com.kholodilin.collector.service.CollectorJobMetrics;
import com.kholodilin.collector.service.CollectorLifecycleGate;
import com.kholodilin.collector.service.JobExecutionService;
import com.kholodilin.collector.service.RecoveryService;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

/** The module still registers acceptance and lifecycle services after the layer split. */
class CollectorWiringTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withBean(JsonMapper.class, () -> JsonMapper.builder().build())
          .withUserConfiguration(
              CollectorModuleConfiguration.class,
              CollectorMemoryConfiguration.class,
              CollectorObservabilityConfiguration.class);

  @Test
  void runtimeEnabledRegistersAcceptanceLifecycleRecoveryAndMetrics() {
    runner
        .withPropertyValues("collector.runtime.enabled=true")
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).hasSingleBean(CollectorAcceptanceService.class);
          assertThat(context).hasSingleBean(CollectorLifecycleGate.class);
          assertThat(context).hasSingleBean(RecoveryService.class);
          assertThat(context).hasSingleBean(CollectorJobMetrics.class);
          assertThat(context).hasSingleBean(JobQueue.class);
          assertThat(context.getBean(RecoveryService.class).executionEnabled()).isFalse();
        });
  }

  @Test
  void runtimeDisabledRegistersNothing() {
    runner.run(context -> {
      assertThat(context).hasNotFailed();
      assertThat(context).doesNotHaveBean(CollectorAcceptanceService.class);
      assertThat(context).doesNotHaveBean(CollectorLifecycleGate.class);
      assertThat(context).doesNotHaveBean(RecoveryService.class);
    });
  }

  @Test
  void recoveryServiceSeesTheExecutionServiceWhenItIsPresent() {
    runner
        .withPropertyValues("collector.runtime.enabled=true")
        .withBean(
            JobExecutionService.class, () -> org.mockito.Mockito.mock(JobExecutionService.class))
        .run(context ->
            assertThat(context.getBean(RecoveryService.class).executionEnabled()).isTrue());
  }

  @Test
  void injectedMetricsAreBoundToMicrometerInsteadOfStaticCounters() {
    runner
        .withPropertyValues("collector.runtime.enabled=true")
        .run(context -> {
          var registry = new SimpleMeterRegistry();
          context.getBean(MeterBinder.class).bindTo(registry);
          var metrics = context.getBean(CollectorJobMetrics.class);

          metrics.recordAccepted();
          metrics.recordAccepted();
          metrics.recordRejectedCapacity();

          assertThat(registry.get("collector.jobs.accepted").functionCounter().count())
              .isEqualTo(2.0);
          assertThat(registry.get("collector.jobs.rejected_capacity").functionCounter().count())
              .isEqualTo(1.0);
          assertThat(registry.get("collector.queue.jobs").gauge().value()).isZero();
          assertThat(registry.get("collector.queue.utilization.jobs").gauge().value()).isZero();
        });
  }
}
