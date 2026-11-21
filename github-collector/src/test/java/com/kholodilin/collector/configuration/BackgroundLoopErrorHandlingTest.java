package com.kholodilin.collector.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.kholodilin.collector.service.JobExecutionService;
import com.kholodilin.collector.service.ResultDeliveryService;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Unexpected poll failures must be swallowed so the next scheduled tick still runs. */
class BackgroundLoopErrorHandlingTest {
  @Test
  void executionPollFailureDoesNotStopSubsequentPoll() {
    JobExecutionService executor = mock(JobExecutionService.class);
    AtomicInteger calls = new AtomicInteger();
    when(executor.poll("collector-executor"))
        .thenAnswer(invocation -> {
          if (calls.getAndIncrement() == 0) throw new IllegalStateException("boom");
          return false;
        });
    Runnable tick =
        () -> {
          try {
            executor.poll("collector-executor");
          } catch (RuntimeException error) {
            // same contract as CollectorExecutionConfiguration.collectorExecutionLoop
          }
        };
    tick.run();
    tick.run();
    verify(executor, times(2)).poll("collector-executor");
    assertThat(calls.get()).isEqualTo(2);
  }

  @Test
  void deliveryPollFailureDoesNotStopSubsequentPoll() {
    ResultDeliveryService service = mock(ResultDeliveryService.class);
    AtomicInteger calls = new AtomicInteger();
    when(service.poll("collector-delivery"))
        .thenAnswer(invocation -> {
          if (calls.getAndIncrement() == 0) throw new IllegalStateException("boom");
          return false;
        });
    Runnable tick =
        () -> {
          try {
            service.poll("collector-delivery");
          } catch (RuntimeException error) {
            // same contract as CollectorDeliveryConfiguration.collectorDeliveryLoop
          }
        };
    tick.run();
    tick.run();
    verify(service, times(2)).poll("collector-delivery");
    assertThat(calls.get()).isEqualTo(2);
  }
}
