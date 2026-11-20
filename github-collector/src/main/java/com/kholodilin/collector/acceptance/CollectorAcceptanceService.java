package com.kholodilin.collector.acceptance;

import com.kholodilin.collector.contracts.CollectorProtocol.*;
import com.kholodilin.collector.idempotency.ScopedIdempotency;
import com.kholodilin.collector.queue.CapacityExceededException;
import com.kholodilin.collector.queue.JobQueue;
import com.kholodilin.idempotency.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "collector.runtime.enabled", havingValue = "true")
public class CollectorAcceptanceService {
  private final @Qualifier("collectorIdempotency") IdempotencyService idempotency;
  private final JobQueue queue;
  private final AcceptanceProperties acceptance;
  private final CollectorLifecycleGate gate;
  private final JsonMapper json;

  /** Origin is resolved from authenticated service identity, never a request field. */
  public JobAccepted accept(String trustedOrigin, String key, JobRequest request) {
    if (!gate.accepting()) {
      throw new CapacityExceededException("Collector is shutting down");
    }
    String scoped = ScopedIdempotency.key(trustedOrigin, key);
    if (!acceptance.destinationRefs().contains(request.destinationRef())) {
      throw new IllegalArgumentException("Unknown result destination");
    }
    CollectorMdcFilter.bindJob(
        request.jobId() == null ? null : request.jobId().toString(),
        request.type() == null ? null : request.type().name());
    return idempotency
        .operation("ACCEPT_COLLECTOR_JOB")
        .key(scoped)
        .request(request)
        .execute(
            JobAccepted.class,
            () -> {
              if (!request.attemptId().toString().equals(key)) {
                throw new IllegalArgumentException("Idempotency-Key must equal attemptId");
              }
              var accepted =
                  queue.accept(
                      new JobQueue.Submission(
                          trustedOrigin,
                          request.jobId(),
                          request.attemptId(),
                          json.writeValueAsString(request),
                          request.destinationRef(),
                          request.deadlineAt()));
              return ExecutionResult.success(
                  new JobAccepted(
                      1,
                      accepted.publicId(),
                      request.jobId(),
                      request.attemptId(),
                      JobState.ACCEPTED,
                      accepted.acceptedAt()));
            })
        .valueOrThrow();
  }
}
