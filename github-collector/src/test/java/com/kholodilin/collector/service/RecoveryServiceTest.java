package com.kholodilin.collector.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.kholodilin.collector.contracts.CollectorProtocol.JobState;
import com.kholodilin.collector.model.LocalJobState;
import com.kholodilin.collector.repository.JobQueue;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class RecoveryServiceTest {
  private JobQueue queue;
  private ObjectProvider<JobExecutionService> execution;
  private RecoveryService service;

  @SuppressWarnings("unchecked")
  @BeforeEach
  void setup() {
    queue = mock(JobQueue.class);
    execution = mock(ObjectProvider.class);
    service = new RecoveryService(queue, execution);
  }

  private static JobQueue.Status view(LocalJobState state, UUID resultId) {
    return new JobQueue.Status(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        state,
        Instant.parse("2030-01-01T00:00:00Z"),
        resultId);
  }

  @Test
  void nodeStatusReportsExecutionAndAcceptingFromTheExecutorBean() {
    when(queue.diagnostics("rgm")).thenReturn(Map.of("queueSize", 3));
    when(execution.getIfAvailable()).thenReturn(mock(JobExecutionService.class));

    var status = service.nodeStatus("rgm");

    assertThat(status)
        .containsEntry("queueSize", 3)
        .containsEntry("schemaVersion", 1)
        .containsEntry("executionEnabled", true)
        .containsEntry("accepting", true)
        .containsEntry("capabilities", List.of("durable-jobs", "status", "result-recovery"));
  }

  @Test
  void nodeStatusOfAnAcceptOnlyNodeIsNotExecuting() {
    when(queue.diagnostics("rgm")).thenReturn(Map.of());
    when(execution.getIfAvailable()).thenReturn(null);

    var status = service.nodeStatus("rgm");

    assertThat(status).containsEntry("executionEnabled", false).containsEntry("accepting", false);
    assertThat(service.executionEnabled()).isFalse();
    assertThat(service.accepting()).isFalse();
  }

  @Test
  void queuedJobIsReportedAsWireAcceptedAndKeepsItsIdentifiers() {
    var view = view(LocalJobState.READY, null);
    when(queue.status(view.publicId(), "rgm")).thenReturn(Optional.of(view));

    var status = service.status(view.publicId(), "rgm").orElseThrow();

    assertThat(status.status()).isEqualTo(JobState.ACCEPTED);
    assertThat(status.collectorJobId()).isEqualTo(view.publicId());
    assertThat(status.jobId()).isEqualTo(view.jobId());
    assertThat(status.attemptId()).isEqualTo(view.attemptId());
    assertThat(status.updatedAt()).isEqualTo(view.updatedAt());
    assertThat(status.failure()).isNull();
  }

  @Test
  void terminalJobCarriesItsResultId() {
    UUID resultId = UUID.randomUUID();
    var view = view(LocalJobState.SUCCEEDED, resultId);
    when(queue.statusByAttempt(view.attemptId(), "rgm")).thenReturn(Optional.of(view));

    var status = service.statusByAttempt(view.attemptId(), "rgm").orElseThrow();

    assertThat(status.status()).isEqualTo(JobState.SUCCEEDED);
    assertThat(status.resultId()).isEqualTo(resultId);
  }

  @Test
  void unknownOrForeignJobsAreEmpty() {
    UUID id = UUID.randomUUID();
    when(queue.status(id, "other")).thenReturn(Optional.empty());
    when(queue.statusByAttempt(id, "other")).thenReturn(Optional.empty());
    when(queue.result(id, "other")).thenReturn(Optional.empty());

    assertThat(service.status(id, "other")).isEmpty();
    assertThat(service.statusByAttempt(id, "other")).isEmpty();
    assertThat(service.result(id, "other")).isEmpty();
  }

  @Test
  void resultAndDeliveryRecoveryDelegateWithTheCallerOrigin() {
    UUID id = UUID.randomUUID();
    UUID event = UUID.randomUUID();
    when(queue.result(id, "rgm")).thenReturn(Optional.of("{\"x\":1}"));
    when(queue.confirmDelivery(id, "rgm", event)).thenReturn(true);
    when(queue.requeueDelivery(id, "rgm")).thenReturn(true);

    assertThat(service.result(id, "rgm")).contains("{\"x\":1}");
    assertThat(service.confirmDelivery(id, "rgm", event)).isTrue();
    assertThat(service.confirmDelivery(id, "other", event)).isFalse();
    assertThat(service.requeueDelivery(id, "rgm")).isTrue();
    assertThat(service.requeueDelivery(id, "other")).isFalse();
  }
}
