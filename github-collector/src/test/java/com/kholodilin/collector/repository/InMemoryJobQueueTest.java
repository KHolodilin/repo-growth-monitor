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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class InMemoryJobQueueTest {
  private static final Duration LEASE = Duration.ofMinutes(1);
  private static final String ORIGIN = "rgm";
  private static final String DESTINATION = "rgm-results";
  private static final String SAMPLE_PAYLOAD = "{\"a\":1,\"b\":2}";
  private static final String RESULT_PAYLOAD = "{\"data\":42}";
  private static final Instant DEADLINE = Instant.parse("2027-01-01T00:00:00Z");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private MutableClock clock;
  private InMemoryJobQueue queue;

  @BeforeEach
  void create() {
    clock = new MutableClock(Instant.parse("2026-06-01T12:00:00Z"));
    queue = new InMemoryJobQueue(10, 1024 * 1024, 64 * 1024, clock, JSON);
  }

  private InMemoryJobQueue queue(int maxJobs, long maxQueueBytes, int maxRequestBytes) {
    return new InMemoryJobQueue(maxJobs, maxQueueBytes, maxRequestBytes, clock, JSON);
  }

  Submission submission() {
    return new Submission(
        ORIGIN, UUID.randomUUID(), UUID.randomUUID(), SAMPLE_PAYLOAD, DESTINATION, DEADLINE);
  }

  Submission submission(String payload) {
    return new Submission(
        ORIGIN, UUID.randomUUID(), UUID.randomUUID(), payload, DESTINATION, DEADLINE);
  }

  /** Assert another thread can enter a lock-taking API within timeout (detects missing unlock). */
  private void assertLockReleased(Callable<?> probe) throws Exception {
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Future<?> future = pool.submit(probe);
      assertThat(future.get(2, TimeUnit.SECONDS)).isNotNull();
    } finally {
      pool.shutdownNow();
      assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  private Accepted acceptAndComplete(String resultPayload) {
    Accepted accepted = queue.accept(submission());
    Lease lease = queue.claim("worker", LEASE).orElseThrow();
    assertThat(
            queue.complete(
                lease, LocalJobState.SUCCEEDED, UUID.randomUUID(), UUID.randomUUID(), resultPayload))
        .isTrue();
    return accepted;
  }

  @Test
  void constructorRejectsInvalidCapacityBoundaries() {
    assertThatThrownBy(() -> queue(0, 1024, 256)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> queue(1, 1023, 256)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> queue(1, 1024, 255)).isInstanceOf(IllegalArgumentException.class);
    assertThatCode(() -> queue(1, 1024, 256)).doesNotThrowAnyException();
  }

  @Test
  void acceptReplayAndConflict() {
    Submission s = submission();
    Accepted first = queue.accept(s);
    assertThat(first.replay()).isFalse();
    assertThat(queue.accept(s))
        .extracting(Accepted::publicId, Accepted::replay)
        .containsExactly(first.publicId(), true);
    assertThatThrownBy(
            () ->
                queue.accept(
                    new Submission(
                        s.origin(),
                        s.jobId(),
                        s.attemptId(),
                        "{\"other\":true}",
                        s.destination(),
                        s.deadline())))
        .isInstanceOf(AttemptConflictException.class);
    assertThatThrownBy(
            () ->
                queue.accept(
                    new Submission(
                        s.origin(),
                        UUID.randomUUID(),
                        s.attemptId(),
                        s.payload(),
                        s.destination(),
                        s.deadline())))
        .isInstanceOf(AttemptConflictException.class);
    // Conflict must not replace the already-accepted job.
    assertThat(queue.size()).isEqualTo(1);
    assertThat(queue.accept(s).publicId()).isEqualTo(first.publicId());
    assertThat(queue.status(first.publicId(), ORIGIN).orElseThrow().state())
        .isEqualTo(LocalJobState.READY);
  }

  @Test
  void capacityRejectsExactBoundariesAndFreesBytesOnArchive() {
    String exact = "x".repeat(256);
    InMemoryJobQueue requestBound = queue(10, 1024 * 1024, 256);
    assertThat(requestBound.accept(submission(exact)).replay()).isFalse();
    assertThatThrownBy(() -> requestBound.accept(submission(exact + "y")))
        .isInstanceOf(CapacityExceededException.class);

    InMemoryJobQueue jobBound = queue(2, 1024 * 1024, 64 * 1024);
    jobBound.accept(submission());
    jobBound.accept(submission());
    assertThat(jobBound.size()).isEqualTo(2);
    assertThatThrownBy(() -> jobBound.accept(submission()))
        .isInstanceOf(CapacityExceededException.class)
        .hasMessageContaining("maxJobs");

    InMemoryJobQueue byteBound = queue(10, 1024, 64 * 1024);
    String first = "a".repeat(600);
    String second = "b".repeat(424);
    byteBound.accept(submission(first));
    assertThat(byteBound.totalBytes()).isEqualTo(600);
    byteBound.accept(submission(second));
    assertThat(byteBound.totalBytes()).isEqualTo(1024);
    assertThatThrownBy(() -> byteBound.accept(submission("c")))
        .isInstanceOf(CapacityExceededException.class)
        .hasMessageContaining("maxQueueBytes");

    // Result bytes are added on top of payload occupancy; leave headroom for a fitting result.
    InMemoryJobQueue resultBound = queue(10, 1024, 64 * 1024);
    resultBound.accept(submission("p".repeat(900)));
    Lease lease = resultBound.claim("w", LEASE).orElseThrow();
    assertThatThrownBy(
            () ->
                resultBound.complete(
                    lease,
                    LocalJobState.SUCCEEDED,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "r".repeat(125)))
        .isInstanceOf(CapacityExceededException.class)
        .hasMessageContaining("maxQueueBytes");
    assertThat(resultBound.totalBytes()).isEqualTo(900);
    // Exact fill of remaining capacity (900 + 124 == 1024) must be allowed.
    assertThat(
            resultBound.complete(
                lease,
                LocalJobState.SUCCEEDED,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "r".repeat(124)))
        .isTrue();
    assertThat(resultBound.totalBytes()).isEqualTo(1024);
    Delivery delivery = resultBound.claimDelivery("s", LEASE).orElseThrow();
    assertThat(resultBound.acknowledge(delivery)).isTrue();
    assertThat(resultBound.archive(lease.id(), lease.generation())).isTrue();
    assertThat(resultBound.totalBytes()).isZero();
    assertThat(resultBound.size()).isZero();
    assertThat(resultBound.accept(submission("freed")).replay()).isFalse();
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
    assertThat(queue.acknowledge(delivery)).isFalse();
    assertThat(queue.claimDelivery("sender", LEASE)).isEmpty();
    assertThat(queue.status(accepted.publicId(), ORIGIN).orElseThrow().state())
        .isEqualTo(LocalJobState.SUCCEEDED);
    assertThat(queue.result(accepted.publicId(), ORIGIN)).contains(RESULT_PAYLOAD);
    assertThat(queue.archive(lease.id(), lease.generation())).isTrue();
    assertThat(queue.archive(lease.id(), lease.generation())).isFalse();
    assertThat(queue.size()).isZero();
  }

  @Test
  void deliveryRetryDeadLetterConfirmAndRequeue() throws Exception {
    Accepted beforeComplete = queue.accept(submission());
    assertThat(queue.confirmDelivery(beforeComplete.publicId(), ORIGIN, UUID.randomUUID()))
        .isFalse();
    assertThat(queue.requeueDelivery(beforeComplete.publicId(), ORIGIN)).isFalse();

    Lease lease = queue.claim("a", LEASE).orElseThrow();
    UUID event = UUID.randomUUID();
    queue.complete(lease, LocalJobState.SUCCEEDED, UUID.randomUUID(), event, "{}");
    // READY is not requeueable until DEAD/RETRY.
    assertThat(queue.requeueDelivery(lease.publicId(), ORIGIN)).isFalse();

    Delivery sender = queue.claimDelivery("sender-a", LEASE).orElseThrow();
    assertThat(queue.requeueDelivery(lease.publicId(), ORIGIN)).isFalse();
    assertThat(queue.deadLetter(sender, DeliveryError.REJECTED)).isTrue();
    assertThat(queue.deadLetter(sender, DeliveryError.REJECTED)).isFalse();
    assertThat(queue.diagnostics(ORIGIN)).containsEntry("deadDeliveries", 1L);
    assertThat(queue.requeueDelivery(lease.publicId(), ORIGIN)).isTrue();
    assertLockReleased(() -> queue.accept(submission("after-requeue")));
    assertThat(queue.requeueDelivery(lease.publicId(), "other")).isFalse();
    assertThat(queue.requeueDelivery(UUID.randomUUID(), ORIGIN)).isFalse();

    Delivery newer = queue.claimDelivery("sender-b", LEASE).orElseThrow();
    assertThat(newer.generation()).isGreaterThan(sender.generation());
    assertThat(queue.acknowledge(sender)).isFalse();
    assertThat(queue.confirmDelivery(lease.publicId(), ORIGIN, UUID.randomUUID())).isFalse();
    assertThat(queue.confirmDelivery(lease.publicId(), "other", event)).isFalse();
    assertThat(queue.confirmDelivery(UUID.randomUUID(), ORIGIN, event)).isFalse();
    assertThat(queue.confirmDelivery(lease.publicId(), ORIGIN, event)).isTrue();
    assertLockReleased(() -> queue.accept(submission("after-confirm")));
    assertThat(queue.confirmDelivery(lease.publicId(), ORIGIN, event)).isTrue();
    assertThat(queue.retryDelivery(newer, clock.instant())).isFalse();
  }

  @Test
  void expiredLeaseIsReclaimedAndOldWorkerIsFenced() {
    queue.accept(submission());
    Lease old = queue.claim("a", Duration.ofSeconds(30)).orElseThrow();
    Instant exactExpiry = clock.instant().plusSeconds(30);
    clock.set(exactExpiry);
    // lockedUntil.isAfter(now) is false at the exact expiry instant.
    assertThat(queue.renew(old, LEASE)).isFalse();
    assertThat(
            queue.complete(old, LocalJobState.FAILED, UUID.randomUUID(), UUID.randomUUID(), "{}"))
        .isFalse();
    Lease current = queue.claim("b", LEASE).orElseThrow();
    assertThat(current.generation()).isEqualTo(old.generation() + 1);
    assertThat(queue.retry(old, clock.instant())).isFalse();
    assertThat(queue.retry(current, clock.instant(), false)).isTrue();
    Lease again = queue.claim("c", LEASE).orElseThrow();
    assertThat(again.failures()).isEqualTo(0);
    assertThat(queue.retry(again, clock.instant(), true)).isTrue();
    Lease counted = queue.claim("d", LEASE).orElseThrow();
    assertThat(counted.failures()).isEqualTo(1);
  }

  @Test
  void wrongOwnerAndStaleGenerationAreRejected() {
    queue.accept(submission());
    Lease lease = queue.claim("owner-a", LEASE).orElseThrow();
    Lease forged =
        new Lease(
            lease.id(),
            "owner-b",
            lease.generation(),
            lease.payload(),
            lease.deadline(),
            lease.origin(),
            lease.publicId(),
            lease.failures());
    Lease staleGeneration =
        new Lease(
            lease.id(),
            lease.owner(),
            lease.generation() - 1,
            lease.payload(),
            lease.deadline(),
            lease.origin(),
            lease.publicId(),
            lease.failures());
    assertThat(queue.renew(forged, LEASE)).isFalse();
    assertThat(queue.renew(staleGeneration, LEASE)).isFalse();
    assertThat(queue.retry(forged, clock.instant())).isFalse();
    assertThat(queue.retry(staleGeneration, clock.instant())).isFalse();
    assertThat(
            queue.complete(
                forged, LocalJobState.SUCCEEDED, UUID.randomUUID(), UUID.randomUUID(), "{}"))
        .isFalse();
    assertThat(
            queue.complete(
                staleGeneration,
                LocalJobState.SUCCEEDED,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "{}"))
        .isFalse();
    assertThat(queue.status(lease.publicId(), ORIGIN).orElseThrow().state())
        .isEqualTo(LocalJobState.RUNNING);
    assertThat(queue.renew(lease, LEASE)).isTrue();
    assertThat(
            queue.complete(
                lease, LocalJobState.SUCCEEDED, UUID.randomUUID(), UUID.randomUUID(), "{}"))
        .isTrue();
    // After terminal completion the lease no longer holds; renew/retry must stay false.
    assertThat(queue.renew(lease, LEASE)).isFalse();
    assertThat(queue.retry(lease, clock.instant())).isFalse();
  }

  @Test
  void expiredDeliveryLeaseIsReclaimedAndOldSenderIsFenced() {
    acceptAndComplete("{}");
    Delivery old = queue.claimDelivery("sender-a", Duration.ofSeconds(10)).orElseThrow();
    clock.set(clock.instant().plusSeconds(10));
    assertThat(queue.acknowledge(old)).isFalse();
    assertThat(queue.retryDelivery(old, clock.instant().plusSeconds(30))).isFalse();
    Delivery current = queue.claimDelivery("sender-b", LEASE).orElseThrow();
    assertThat(current.generation()).isEqualTo(old.generation() + 1);
    assertThat(queue.acknowledge(old)).isFalse();
    assertThat(queue.retryDelivery(current, clock.instant().plusSeconds(5))).isTrue();
    assertThat(queue.claimDelivery("sender-c", LEASE)).isEmpty();
    clock.set(clock.instant().plusSeconds(5));
    Delivery afterRetry = queue.claimDelivery("sender-c", LEASE).orElseThrow();
    assertThat(queue.acknowledge(afterRetry)).isTrue();
  }

  @Test
  void acknowledgeBooleanAndStateAreConsistent() {
    Accepted accepted = acceptAndComplete(RESULT_PAYLOAD);
    Delivery delivery = queue.claimDelivery("sender", LEASE).orElseThrow();
    assertThat(queue.acknowledge(delivery)).isTrue();
    assertThat(queue.claimDelivery("sender", LEASE)).isEmpty();
    assertThat(queue.result(accepted.publicId(), ORIGIN)).contains(RESULT_PAYLOAD);
    assertThat(queue.acknowledge(delivery)).isFalse();
  }

  @Test
  void concurrentAcceptIsIdempotent() throws Exception {
    Submission s = submission();
    ExecutorService pool = Executors.newFixedThreadPool(5);
    try {
      List<Callable<Accepted>> tasks = new ArrayList<>();
      for (int i = 0; i < 10; i++) {
        tasks.add(() -> queue.accept(s));
      }
      Set<UUID> ids = new HashSet<>();
      int fresh = 0;
      for (var f : pool.invokeAll(tasks)) {
        var a = f.get(5, TimeUnit.SECONDS);
        ids.add(a.publicId());
        if (!a.replay()) {
          fresh++;
        }
      }
      assertThat(ids).hasSize(1);
      assertThat(fresh).isEqualTo(1);
      assertThat(queue.size()).isEqualTo(1);
    } finally {
      pool.shutdownNow();
      assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void concurrentClaimGivesJobToOnlyOneWorkerWhileLeaseHolds() throws Exception {
    queue.accept(submission());
    ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      CyclicBarrier start = new CyclicBarrier(8);
      List<Callable<Optional<Lease>>> tasks = new ArrayList<>();
      for (int i = 0; i < 8; i++) {
        String worker = "w-" + i;
        tasks.add(
            () -> {
              start.await(2, TimeUnit.SECONDS);
              return queue.claim(worker, LEASE);
            });
      }
      List<Optional<Lease>> results = new ArrayList<>();
      for (Future<Optional<Lease>> future : pool.invokeAll(tasks)) {
        results.add(future.get(5, TimeUnit.SECONDS));
      }
      List<Lease> claimed = results.stream().flatMap(Optional::stream).toList();
      assertThat(claimed).hasSize(1);
      assertThat(queue.claim("late", LEASE)).isEmpty();
    } finally {
      pool.shutdownNow();
      assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void missingUnlockIsVisibleToAnotherThread() throws Exception {
    queue.accept(submission());
    Lease lease = queue.claim("owner", LEASE).orElseThrow();
    // Probe must take the lock without consuming the held job/delivery.
    assertLockReleased(() -> queue.accept(submission("probe-1")));

    assertThat(
            queue.complete(
                lease, LocalJobState.SUCCEEDED, UUID.randomUUID(), UUID.randomUUID(), "{}"))
        .isTrue();
    assertLockReleased(() -> queue.accept(submission("probe-2")));

    Delivery delivery = queue.claimDelivery("sender", LEASE).orElseThrow();
    assertThat(queue.acknowledge(delivery)).isTrue();
    assertLockReleased(() -> queue.accept(submission("probe-3")));

    assertThat(queue.archive(lease.id(), lease.generation())).isTrue();
    assertLockReleased(() -> queue.accept(submission("probe-4")));
  }

  @Test
  void renewUnlockAndFalsePaths() throws Exception {
    queue.accept(submission());
    Lease lease = queue.claim("a", LEASE).orElseThrow();
    assertThat(queue.renew(lease, LEASE)).isTrue();
    assertLockReleased(() -> queue.claim("probe", LEASE));
    clock.set(clock.instant().plus(LEASE).plusMillis(1));
    assertThat(queue.renew(lease, LEASE)).isFalse();
  }

  @Test
  void retryUnlockAndFalsePaths() throws Exception {
    queue.accept(submission());
    Lease lease = queue.claim("a", LEASE).orElseThrow();
    assertThat(queue.retry(lease, clock.instant().plusSeconds(10))).isTrue();
    assertLockReleased(() -> queue.accept(submission()));
    assertThat(queue.retry(lease, clock.instant())).isFalse();
  }

  @Test
  void archiveDeliveredRemovesOneTerminalDeliveredJob() throws Exception {
    acceptAndComplete("{}");
    assertThat(queue.archiveDelivered()).isFalse();
    Delivery delivery = queue.claimDelivery("s", LEASE).orElseThrow();
    assertThat(queue.archiveDelivered()).isFalse();
    assertThat(queue.acknowledge(delivery)).isTrue();
    assertThat(queue.size()).isEqualTo(1);
    assertThat(queue.archiveDelivered()).isTrue();
    assertThat(queue.size()).isZero();
    assertLockReleased(() -> queue.archiveDelivered());
    assertThat(queue.archiveDelivered()).isFalse();
  }

  @Test
  void archiveRejectsWrongGenerationAndNonDelivered() {
    Accepted accepted = queue.accept(submission());
    Lease lease = queue.claim("a", LEASE).orElseThrow();
    assertThat(queue.archive(lease.id(), lease.generation())).isFalse();
    assertThat(
            queue.complete(
                lease, LocalJobState.SUCCEEDED, UUID.randomUUID(), UUID.randomUUID(), "{}"))
        .isTrue();
    assertThat(queue.archive(lease.id(), lease.generation())).isFalse();
    Delivery delivery = queue.claimDelivery("s", LEASE).orElseThrow();
    assertThat(queue.acknowledge(delivery)).isTrue();
    assertThat(queue.archive(lease.id(), lease.generation() + 1)).isFalse();
    assertThat(queue.find(accepted.publicId())).isPresent();
    assertThat(queue.archive(lease.id(), lease.generation())).isTrue();
    assertThat(queue.find(accepted.publicId())).isEmpty();
  }

  @Test
  void statusResultAndDiagnosticsRespectOriginAndAttempt() {
    Accepted accepted = queue.accept(submission());
    assertThat(queue.statusByAttempt(accepted.publicId(), ORIGIN)).isEmpty();
    UUID attempt =
        queue.status(accepted.publicId(), ORIGIN).orElseThrow().attemptId();
    assertThat(queue.statusByAttempt(attempt, ORIGIN)).isPresent();
    assertThat(queue.statusByAttempt(attempt, "other")).isEmpty();
    assertThat(queue.status(accepted.publicId(), "other")).isEmpty();
    assertThat(queue.result(accepted.publicId(), ORIGIN)).isEmpty();
    assertThat(queue.diagnostics(ORIGIN)).containsEntry("pending", 1L);
    assertThat(queue.diagnostics("other")).containsEntry("pending", 0L);

    Lease lease = queue.claim("w", LEASE).orElseThrow();
    queue.complete(lease, LocalJobState.SUCCEEDED, UUID.randomUUID(), UUID.randomUUID(), "{\"x\":1}");
    assertThat(queue.result(accepted.publicId(), ORIGIN)).contains("{\"x\":1}");
    assertThat(queue.result(accepted.publicId(), "other")).isEmpty();
    assertThat(queue.diagnostics(ORIGIN)).containsEntry("pending", 0L);
  }

  @Test
  void ownerAndLeaseDurationValidation() {
    assertThatThrownBy(() -> queue.accept(
            new Submission("", UUID.randomUUID(), UUID.randomUUID(), "{}", DESTINATION, DEADLINE)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> queue.accept(
            new Submission(
                "   ", UUID.randomUUID(), UUID.randomUUID(), "{}", DESTINATION, DEADLINE)))
        .isInstanceOf(IllegalArgumentException.class);
    String tooLong = "o".repeat(129);
    assertThatThrownBy(() -> queue.claim(tooLong, LEASE))
        .isInstanceOf(IllegalArgumentException.class);
    String maxOwner = "o".repeat(128);
    queue.accept(submission());
    assertThat(queue.claim(maxOwner, Duration.ofMillis(1))).isPresent();
    assertThatThrownBy(() -> queue.claim("w", Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> queue.claim("w", Duration.ofHours(1).plusMillis(1)))
        .isInstanceOf(IllegalArgumentException.class);
    queue.accept(submission());
    assertThat(queue.claim("w", Duration.ofHours(1))).isPresent();
  }

  @Test
  void claimOrderPrefersEarlierNextAttemptThenLowerId() {
    Accepted first = queue.accept(submission());
    Accepted second = queue.accept(submission());
    Lease a = queue.claim("w1", LEASE).orElseThrow();
    Lease b = queue.claim("w2", LEASE).orElseThrow();
    assertThat(a.publicId()).isEqualTo(first.publicId());
    assertThat(b.publicId()).isEqualTo(second.publicId());
    assertThat(queue.retry(b, clock.instant().plusSeconds(5))).isTrue();
    assertThat(queue.retry(a, clock.instant().plusSeconds(10))).isTrue();
    clock.set(clock.instant().plusSeconds(5));
    Lease next = queue.claim("w3", LEASE).orElseThrow();
    assertThat(next.publicId()).isEqualTo(second.publicId());
    clock.set(clock.instant().plusSeconds(5));
    Lease last = queue.claim("w4", LEASE).orElseThrow();
    assertThat(last.publicId()).isEqualTo(first.publicId());
  }

  @Test
  void deliveryClaimOrderPrefersEarlierNextAttemptThenLowerId() {
    Accepted first = acceptAndComplete("{\"n\":1}");
    Accepted second = acceptAndComplete("{\"n\":2}");
    Delivery early = queue.claimDelivery("s1", LEASE).orElseThrow();
    assertThat(early.jobId()).isEqualTo(first.id());
    assertThat(queue.retryDelivery(early, clock.instant().plusSeconds(10))).isTrue();
    Delivery laterJob = queue.claimDelivery("s2", LEASE).orElseThrow();
    assertThat(laterJob.jobId()).isEqualTo(second.id());
    assertThat(queue.retryDelivery(laterJob, clock.instant().plusSeconds(5))).isTrue();
    clock.set(clock.instant().plusSeconds(5));
    Delivery next = queue.claimDelivery("s3", LEASE).orElseThrow();
    assertThat(next.jobId()).isEqualTo(second.id());
    clock.set(clock.instant().plusSeconds(5));
    Delivery last = queue.claimDelivery("s4", LEASE).orElseThrow();
    assertThat(last.jobId()).isEqualTo(first.id());
  }

  @Test
  void sameNextAttemptDeliveryPrefersLowerDeliveryId() {
    Accepted first = acceptAndComplete("{\"a\":1}");
    Accepted second = acceptAndComplete("{\"a\":2}");
    Delivery firstDelivery = queue.claimDelivery("s", LEASE).orElseThrow();
    assertThat(firstDelivery.jobId()).isEqualTo(first.id());
    assertThat(queue.acknowledge(firstDelivery)).isTrue();
    Delivery secondDelivery = queue.claimDelivery("s", LEASE).orElseThrow();
    assertThat(secondDelivery.jobId()).isEqualTo(second.id());
  }

  @Test
  void metricsTrackBytesAndInFlight() {
    queue.accept(submission());
    assertThat(queue.jobCountsByState()).containsEntry(LocalJobState.READY.name(), 1L);
    assertThat(queue.totalBytes()).isPositive();
    assertThat(queue.size()).isEqualTo(1);
    Lease lease = queue.claim("w", LEASE).orElseThrow();
    assertThat(queue.inFlight()).isEqualTo(1);
    queue.complete(
        lease, LocalJobState.SUCCEEDED, UUID.randomUUID(), UUID.randomUUID(), "{\"ok\":true}");
    assertThat(queue.jobCountsByState()).containsEntry(LocalJobState.SUCCEEDED.name(), 1L);
    Delivery delivery = queue.claimDelivery("s", LEASE).orElseThrow();
    assertThat(queue.inFlight()).isEqualTo(1);
    assertThat(queue.acknowledge(delivery)).isTrue();
    assertThat(queue.inFlight()).isZero();
  }

  @Test
  void findSnapshotOmitsResultUntilComplete() {
    Accepted accepted = queue.accept(submission());
    JsonNode snapshot = JSON.readTree(queue.find(accepted.publicId()).orElseThrow().snapshot());
    assertThat(snapshot.path("job").path("id").asLong()).isEqualTo(accepted.id());
    assertThat(snapshot.path("job").path("state").asText()).isEqualTo(LocalJobState.READY.name());
    assertThat(snapshot.path("job").path("generation").asLong()).isZero();
    assertThat(snapshot.has("result")).isFalse();
  }

  @Test
  void findSnapshotEmbedsResultPayloadAsObjectWithoutDoubleEncoding() {
    Accepted accepted = queue.accept(submission());
    Lease lease = queue.claim("w", LEASE).orElseThrow();
    UUID resultId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    String nested = "{\"note\":\"quote \\\"slash\\\\ and юникод\",\"n\":1,\"inner\":{\"ok\":true}}";
    assertThat(queue.complete(lease, LocalJobState.SUCCEEDED, resultId, eventId, nested)).isTrue();

    JsonNode snapshot = JSON.readTree(queue.find(accepted.publicId()).orElseThrow().snapshot());
    assertThat(snapshot.path("job").path("state").asText())
        .isEqualTo(LocalJobState.SUCCEEDED.name());
    assertThat(snapshot.path("result").path("public_id").asText()).isEqualTo(resultId.toString());
    assertThat(snapshot.path("result").path("event_id").asText()).isEqualTo(eventId.toString());
    JsonNode payload = snapshot.path("result").path("payload");
    assertThat(payload.isObject()).isTrue();
    assertThat(payload.path("note").asText()).isEqualTo("quote \"slash\\ and юникод");
    assertThat(payload.path("n").asInt()).isEqualTo(1);
    assertThat(payload.path("inner").path("ok").asBoolean()).isTrue();
    assertThat(payload.isValueNode()).isFalse();
  }

  @Test
  void clearResetsObservableState() throws Exception {
    Submission s = submission();
    Accepted accepted = queue.accept(s);
    queue.claim("w", LEASE);
    queue.clear();
    assertLockReleased(() -> queue.accept(submission("after-clear")));
    assertThat(queue.size()).isEqualTo(1);
    assertThat(queue.totalBytes()).isPositive();
    queue.clear();
    assertThat(queue.size()).isZero();
    assertThat(queue.totalBytes()).isZero();
    assertThat(queue.claim("w", LEASE)).isEmpty();
    assertThat(queue.find(accepted.publicId())).isEmpty();
    // Attempt index must be cleared; same attempt is accepted fresh, not as a conflict/replay.
    Accepted again = queue.accept(s);
    assertThat(again.replay()).isFalse();
    assertThat(again.publicId()).isNotEqualTo(accepted.publicId());
  }

  @Test
  void hashIncludesDestinationAndDeadlineForIdempotency() {
    UUID attempt = UUID.randomUUID();
    UUID job = UUID.randomUUID();
    Submission base =
        new Submission(ORIGIN, job, attempt, SAMPLE_PAYLOAD, DESTINATION, DEADLINE);
    queue.accept(base);
    assertThatThrownBy(
            () ->
                queue.accept(
                    new Submission(
                        ORIGIN, job, attempt, SAMPLE_PAYLOAD, "other-dest", DEADLINE)))
        .isInstanceOf(AttemptConflictException.class);
    assertThatThrownBy(
            () ->
                queue.accept(
                    new Submission(
                        ORIGIN,
                        job,
                        attempt,
                        SAMPLE_PAYLOAD,
                        DESTINATION,
                        DEADLINE.plusSeconds(1))))
        .isInstanceOf(AttemptConflictException.class);
    assertThat(queue.accept(base).replay()).isTrue();
  }

  @Test
  void hashSeparatorsPreventConcatenationCollisions() {
    // Without the payload|destination 0-byte separator these collide and would replay.
    UUID attempt = UUID.randomUUID();
    UUID job = UUID.randomUUID();
    queue.accept(new Submission(ORIGIN, job, attempt, "ab", "c", DEADLINE));
    assertThatThrownBy(
            () -> queue.accept(new Submission(ORIGIN, job, attempt, "a", "bc", DEADLINE)))
        .isInstanceOf(AttemptConflictException.class);
    assertThat(queue.size()).isEqualTo(1);

    // Without the destination|deadline separator, dest/epoch digit joins collide.
    UUID attempt2 = UUID.randomUUID();
    UUID job2 = UUID.randomUUID();
    queue.accept(
        new Submission(ORIGIN, job2, attempt2, "p", "12", Instant.ofEpochMilli(34)));
    assertThatThrownBy(
            () ->
                queue.accept(
                    new Submission(
                        ORIGIN, job2, attempt2, "p", "1", Instant.ofEpochMilli(234))))
        .isInstanceOf(AttemptConflictException.class);
  }

  @Test
  void wrongDeliveryOwnerIsRejectedWithoutStateChange() {
    acceptAndComplete("{}");
    Delivery delivery = queue.claimDelivery("sender-a", LEASE).orElseThrow();
    Delivery forged =
        new Delivery(
            delivery.id(),
            "sender-b",
            delivery.generation(),
            delivery.destination(),
            delivery.payload(),
            delivery.origin(),
            delivery.jobId(),
            delivery.jobGeneration());
    assertThat(queue.acknowledge(forged)).isFalse();
    assertThat(queue.retryDelivery(forged, clock.instant().plusSeconds(1))).isFalse();
    assertThat(queue.deadLetter(forged, DeliveryError.REJECTED)).isFalse();
    assertThat(queue.acknowledge(delivery)).isTrue();
  }

  private static final class MutableClock extends Clock {
    private final AtomicReference<Instant> now;
    private final ZoneId zone = ZoneOffset.UTC;

    MutableClock(Instant initial) {
      this.now = new AtomicReference<>(initial);
    }

    void set(Instant instant) {
      now.set(instant);
    }

    @Override
    public ZoneId getZone() {
      return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now.get();
    }
  }
}
