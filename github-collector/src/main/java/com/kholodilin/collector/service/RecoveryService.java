package com.kholodilin.collector.service;

import com.kholodilin.collector.contracts.CollectorProtocol.JobStatus;
import com.kholodilin.collector.repository.JobQueue;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Status, diagnostics and result recovery for an authenticated origin. Everything is scoped to the
 * caller's origin; unknown and foreign jobs are indistinguishable.
 */
@RequiredArgsConstructor
public final class RecoveryService {
  private final JobQueue queue;
  private final ObjectProvider<JobExecutionService> execution;

  /** Whether this node executes assignments (the executor bean exists). */
  public boolean executionEnabled() {
    return execution.getIfAvailable() != null;
  }

  /** Whether this node is currently willing to take work; mirrors {@link #executionEnabled()}. */
  public boolean accepting() {
    return executionEnabled();
  }

  public Map<String, Object> nodeStatus(String origin) {
    Map<String, Object> values = new LinkedHashMap<>(queue.diagnostics(origin));
    values.put("schemaVersion", 1);
    values.put("executionEnabled", executionEnabled());
    values.put("accepting", accepting());
    values.put("capabilities", List.of("durable-jobs", "status", "result-recovery"));
    return values;
  }

  public Optional<JobStatus> statusByAttempt(UUID attemptId, String origin) {
    return queue.statusByAttempt(attemptId, origin).map(RecoveryService::wire);
  }

  public Optional<JobStatus> status(UUID publicId, String origin) {
    return queue.status(publicId, origin).map(RecoveryService::wire);
  }

  /** The stored terminal result envelope exactly as it will be delivered. */
  public Optional<String> result(UUID publicId, String origin) {
    return queue.result(publicId, origin);
  }

  public boolean confirmDelivery(UUID publicId, String origin, UUID eventId) {
    return queue.confirmDelivery(publicId, origin, eventId);
  }

  public boolean requeueDelivery(UUID publicId, String origin) {
    return queue.requeueDelivery(publicId, origin);
  }

  private static JobStatus wire(JobQueue.Status view) {
    return new JobStatus(
        1,
        view.publicId(),
        view.jobId(),
        view.attemptId(),
        JobStateMapper.toWire(view.state()),
        view.updatedAt(),
        view.resultId(),
        null);
  }
}
