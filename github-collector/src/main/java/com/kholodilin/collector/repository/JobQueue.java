package com.kholodilin.collector.repository;

import com.kholodilin.collector.model.DeliveryError;
import com.kholodilin.collector.model.LocalJobState;
import java.time.*;
import java.util.*;

/** Local execution/outbox port. No GitHub or HTTP work runs in these mutations. */
public interface JobQueue {
  record Submission(
      String origin,
      UUID jobId,
      UUID attemptId,
      String payload,
      String destination,
      Instant deadline) {}

  record Accepted(long id, UUID publicId, String state, boolean replay, Instant acceptedAt) {}

  record Lease(
      long id,
      String owner,
      long generation,
      String payload,
      Instant deadline,
      String origin,
      UUID publicId,
      int failures) {}

  record Delivery(
      long id,
      String owner,
      long generation,
      String destination,
      String payload,
      String origin,
      long jobId,
      long jobGeneration) {}

  /** Process-local view of a job's lifecycle; wire mapping belongs to the service layer. */
  record Status(
      UUID publicId,
      UUID jobId,
      UUID attemptId,
      LocalJobState state,
      Instant updatedAt,
      UUID resultId) {}

  record Stored(Accepted identity, Instant archivedAt, String snapshot) {}

  Accepted accept(Submission submission);

  Optional<Lease> claim(String owner, Duration duration);

  boolean renew(Lease lease, Duration duration);

  boolean retry(Lease lease, Instant nextAttempt);

  boolean retry(Lease lease, Instant nextAttempt, boolean countFailure);

  /**
   * Persists a terminal outcome; {@code terminalState} must satisfy {@link
   * LocalJobState#isTerminal()}.
   */
  boolean complete(
      Lease lease, LocalJobState terminalState, UUID resultId, UUID eventId, String payload);

  Optional<Delivery> claimDelivery(String owner, Duration duration);

  boolean acknowledge(Delivery delivery);

  boolean retryDelivery(Delivery delivery, Instant nextAttempt);

  boolean deadLetter(Delivery delivery, DeliveryError error);

  Optional<Status> statusByAttempt(UUID attemptId, String origin);

  Map<String, Object> diagnostics(String origin);

  Optional<Status> status(UUID publicId, String origin);

  Optional<String> result(UUID publicId, String origin);

  boolean confirmDelivery(UUID publicId, String origin, UUID eventId);

  boolean requeueDelivery(UUID publicId, String origin);

  boolean archiveDelivered();

  boolean archive(long jobId, long expectedGeneration);

  Optional<Stored> find(UUID publicId);
}
