package com.kholodilin.collector.repository;

import static com.kholodilin.collector.repository.JobQueue.*;
import static org.assertj.core.api.Assertions.*;

import com.kholodilin.collector.model.AttemptConflictException;
import com.kholodilin.collector.model.CapacityExceededException;
import com.kholodilin.collector.model.DeliveryError;
import com.kholodilin.collector.model.LocalJobState;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InMemoryJobQueueTest {
  private static final Duration LEASE = Duration.ofMinutes(1);
  private static final String ORIGIN = "rgm";
  private static final String DESTINATION = "rgm-results";
  private static final String SAMPLE_PAYLOAD = "{\"a\":1,\"b\":2}";
  private static final String RESULT_PAYLOAD = "{\"data\":42}";
  private static final Instant DEADLINE = Instant.parse("2027-01-01T00:00:00Z");

  InMemoryJobQueue queue;

  @BeforeEach
  void create() {
    queue = new InMemoryJobQueue(10, 1024 * 1024, 64 * 1024);
  }

  Submission submission() {
    return new Submission(
        ORIGIN, UUID.randomUUID(), UUID.randomUUID(), SAMPLE_PAYLOAD, DESTINATION, DEADLINE);
  }

  @Test
  void acceptReplayAndConflict() {
    Submission s = submission();
    Accepted first = queue.accept(s);
    assertThat(first.replay()).isFalse();
    assertThat(queue.accept(s))
        .extracting(Accepted::publicId, Accepted::replay)
        .containsExactly(first.publicId(), true);
    assertThatThrownBy(() ->
            queue.accept(
                new Submission(
                    s.origin(),
                    s.jobId(),
                    s.attemptId(),
                    "{\"other\":true}",
                    s.destination(),
                    s.deadline())))
        .isInstanceOf(AttemptConflictException.class);
    assertThatThrownBy(() ->
            queue.accept(
                new Submission(
                    s.origin(),
                    UUID.randomUUID(),
                    s.attemptId(),
                    s.payload(),
                    s.destination(),
                    s.deadline())))
        .isInstanceOf(AttemptConflictException.class);
  }

  @Test
  void capacityRejectsOversizedRequestAndFullQueue() {
    assertThatThrownBy(() ->
            queue.accept(
                new Submission(
                    ORIGIN,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "x".repeat(65 * 1024),
                    DESTINATION,
                    Instant.now())))
        .isInstanceOf(CapacityExceededException.class);
    InMemoryJobQueue tiny = new InMemoryJobQueue(1, 1024 * 1024, 64 * 1024);
    tiny.accept(submission());
    assertThatThrownBy(() -> tiny.accept(submission()))
        .isInstanceOf(CapacityExceededException.class);
    InMemoryJobQueue tightBytes = new InMemoryJobQueue(10, 1024, 64 * 1024);
    String oversized = "{\"x\":\"" + "y".repeat(2000) + "\"}";
    assertThatThrownBy(() ->
            tightBytes.accept(
                new Submission(
                    ORIGIN,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    oversized,
                    DESTINATION,
                    Instant.now())))
        .isInstanceOf(CapacityExceededException.class);
  }

  @Test
  void claimCompleteAndDeliveryLifecycle() {
    Submission s = submission();
    Accepted accepted = queue.accept(s);
    Lease lease = queue.claim("worker-a", LEASE).orElseThrow();
    assertThat(lease.publicId()).isEqualTo(accepted.publicId());
    assertThat(queue.claim("worker-b", LEASE)).isEmpty();
    assertThat(queue.renew(lease, LEASE)).isTrue();
    UUID resultId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    assertThatThrownBy(() -> queue.complete(lease, LocalJobState.RUNNING, resultId, eventId, "{}"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Terminal");
    assertThat(queue.complete(lease, LocalJobState.SUCCEEDED, resultId, eventId, RESULT_PAYLOAD))
        .isTrue();
    assertThat(queue.complete(lease, LocalJobState.SUCCEEDED, resultId, eventId, RESULT_PAYLOAD))
        .isFalse();
    Delivery delivery = queue.claimDelivery("sender", LEASE).orElseThrow();
    assertThat(delivery.payload()).contains("42");
    assertThat(queue.acknowledge(delivery)).isTrue();
    assertThat(queue.claimDelivery("sender", LEASE)).isEmpty();
    assertThat(queue.status(accepted.publicId(), ORIGIN).orElseThrow().state())
        .isEqualTo(LocalJobState.SUCCEEDED);
    assertThat(queue.result(accepted.publicId(), ORIGIN)).contains(RESULT_PAYLOAD);
    assertThat(queue.archive(lease.id(), lease.generation())).isTrue();
    assertThat(queue.size()).isZero();
  }

  @Test
  void deliveryRetryDeadLetterConfirmAndRequeue() {
    queue.accept(submission());
    Lease lease = queue.claim("a", LEASE).orElseThrow();
    UUID event = UUID.randomUUID();
    queue.complete(lease, LocalJobState.SUCCEEDED, UUID.randomUUID(), event, "{}");
    Delivery sender = queue.claimDelivery("sender-a", LEASE).orElseThrow();
    assertThat(queue.deadLetter(sender, DeliveryError.REJECTED)).isTrue();
    assertThat(queue.diagnostics(ORIGIN)).containsEntry("deadDeliveries", 1L);
    assertThat(queue.requeueDelivery(lease.publicId(), ORIGIN)).isTrue();
    Delivery newer = queue.claimDelivery("sender-b", LEASE).orElseThrow();
    assertThat(queue.acknowledge(sender)).isFalse();
    assertThat(queue.confirmDelivery(lease.publicId(), ORIGIN, UUID.randomUUID())).isFalse();
    assertThat(queue.confirmDelivery(lease.publicId(), ORIGIN, event)).isTrue();
    assertThat(queue.retryDelivery(newer, Instant.now())).isFalse();
  }

  @Test
  void expiredLeaseIsReclaimedAndOldWorkerIsFenced() throws Exception {
    queue.accept(submission());
    Lease old = queue.claim("a", Duration.ofMillis(50)).orElseThrow();
    Thread.sleep(80);
    assertThat(queue.renew(old, LEASE)).isFalse();
    assertThat(
            queue.complete(old, LocalJobState.FAILED, UUID.randomUUID(), UUID.randomUUID(), "{}"))
        .isFalse();
    Lease current = queue.claim("b", LEASE).orElseThrow();
    assertThat(current.generation()).isEqualTo(old.generation() + 1);
    assertThat(queue.retry(old, Instant.now())).isFalse();
    assertThat(queue.retry(current, Instant.now().plusSeconds(600))).isTrue();
    assertThat(queue.claim("c", LEASE)).isEmpty();
  }

  @Test
  void concurrentAcceptIsIdempotent() throws Exception {
    Submission s = submission();
    try (var pool = Executors.newFixedThreadPool(5)) {
      List<Callable<Accepted>> tasks = new ArrayList<>();
      for (int i = 0; i < 10; i++) {
        tasks.add(() -> queue.accept(s));
      }
      Set<UUID> ids = new HashSet<>();
      int fresh = 0;
      for (var f : pool.invokeAll(tasks)) {
        var a = f.get();
        ids.add(a.publicId());
        if (!a.replay()) {
          fresh++;
        }
      }
      assertThat(ids).hasSize(1);
      assertThat(fresh).isEqualTo(1);
    }
  }

  @Test
  void metricsTrackBytesAndInFlight() {
    queue.accept(submission());
    assertThat(queue.jobCountsByState()).containsEntry(LocalJobState.READY.name(), 1L);
    assertThat(queue.totalBytes()).isPositive();
    Lease lease = queue.claim("w", LEASE).orElseThrow();
    assertThat(queue.inFlight()).isEqualTo(1);
    queue.complete(
        lease, LocalJobState.SUCCEEDED, UUID.randomUUID(), UUID.randomUUID(), "{\"ok\":true}");
    assertThat(queue.jobCountsByState()).containsEntry(LocalJobState.SUCCEEDED.name(), 1L);
  }
}
