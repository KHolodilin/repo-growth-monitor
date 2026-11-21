package com.kholodilin.collector.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kholodilin.collector.configuration.CollectorMemoryConfiguration;
import com.kholodilin.collector.configuration.CollectorModuleConfiguration;
import com.kholodilin.collector.contracts.CollectorProtocol.*;
import com.kholodilin.collector.logging.CollectorMdc;
import com.kholodilin.collector.model.CapacityExceededException;
import com.kholodilin.collector.repository.JobQueue;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

class CollectorAcceptanceServiceTest {
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withBean(JsonMapper.class, () -> JsonMapper.builder().build())
          .withUserConfiguration(
              CollectorModuleConfiguration.class, CollectorMemoryConfiguration.class)
          .withPropertyValues("collector.runtime.enabled=true");

  @AfterEach
  void clearMdc() {
    CollectorMdc.clear();
  }

  private static JobRequest request(UUID attempt, String destination) {
    return new JobRequest(
        1,
        UUID.randomUUID(),
        UUID.randomUUID(),
        attempt,
        TaskType.STATS,
        new RepositoryTarget("202", "owner", "repo"),
        LocalDate.of(2030, 1, 1),
        1,
        Parameters.repository(),
        destination,
        Instant.now().plus(Duration.ofHours(12)),
        new AuthorizationScope("context", "github-main"));
  }

  @Test
  void acceptsOnceAndReplaysTheSameAnswerForTheSameKeyAndContent() {
    runner.run(context -> {
      var service = context.getBean(CollectorAcceptanceService.class);
      UUID attempt = UUID.randomUUID();
      var request = request(attempt, "rgm-results");

      var first = service.accept("rgm", attempt.toString(), request);
      var replay = service.accept("rgm", attempt.toString(), request);

      assertThat(first.status()).isEqualTo(JobState.ACCEPTED);
      assertThat(replay.collectorJobId()).isEqualTo(first.collectorJobId());
      assertThat(context.getBean(JobQueue.class).status(first.collectorJobId(), "rgm")).isPresent();
      assertThat(context.getBean(JobQueue.class).status(first.collectorJobId(), "other")).isEmpty();
      assertThat(MDC.get(CollectorMdc.JOB)).isEqualTo(request.jobId().toString());
      assertThat(MDC.get(CollectorMdc.TASK)).isEqualTo("STATS");
    });
  }

  @Test
  void sameKeyFromAnotherOriginIsAnIndependentAcceptance() {
    runner.run(context -> {
      var service = context.getBean(CollectorAcceptanceService.class);
      UUID attempt = UUID.randomUUID();
      var request = request(attempt, "rgm-results");

      var first = service.accept("rgm", attempt.toString(), request);
      var other = service.accept("other", attempt.toString(), request);

      assertThat(other.collectorJobId()).isNotEqualTo(first.collectorJobId());
    });
  }

  @Test
  void rejectsUnknownDestinationsAndKeysThatDifferFromTheAttempt() {
    runner.run(context -> {
      var service = context.getBean(CollectorAcceptanceService.class);
      UUID attempt = UUID.randomUUID();

      assertThatThrownBy(
              () -> service.accept("rgm", attempt.toString(), request(attempt, "elsewhere")))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() ->
              service.accept("rgm", UUID.randomUUID().toString(), request(attempt, "rgm-results")))
          .isInstanceOf(IllegalArgumentException.class);
    });
  }

  @Test
  void stopsAcceptingOnceTheLifecycleGateIsClosed() {
    runner.run(context -> {
      context.getBean(CollectorLifecycleGate.class).stop();
      UUID attempt = UUID.randomUUID();

      assertThatThrownBy(() ->
              context
                  .getBean(CollectorAcceptanceService.class)
                  .accept("rgm", attempt.toString(), request(attempt, "rgm-results")))
          .isInstanceOf(CapacityExceededException.class);
    });
  }
}
