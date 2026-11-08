package com.kholodilin.collector.queue;

import java.time.*;
import java.util.*;

/** Durable local execution/outbox port. No GitHub or HTTP work runs in these transactions. */
public interface JobQueue {
    record Submission(String origin, UUID jobId, UUID attemptId, String payload, String destination, Instant deadline) { }
    record Accepted(long id, UUID publicId, String state, boolean replay, Instant acceptedAt) { }
    record Lease(long id, String owner, long generation, String payload, Instant deadline) { }
    record Delivery(long id, String owner, long generation, String destination, String payload) { }
    record Stored(Accepted identity, Instant archivedAt, String snapshot) { }
    Accepted accept(Submission submission);
    Optional<Lease> claim(String owner, Duration duration);
    boolean renew(Lease lease, Duration duration);
    boolean retry(Lease lease, Instant nextAttempt);
    boolean complete(Lease lease, String terminalState, UUID resultId, UUID eventId, String payload);
    Optional<Delivery> claimDelivery(String owner, Duration duration);
    boolean acknowledge(Delivery delivery);
    boolean retryDelivery(Delivery delivery, Instant nextAttempt);
    boolean archive(long jobId, long expectedGeneration);
    Optional<Stored> find(UUID publicId);
}
