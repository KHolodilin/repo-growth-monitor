package com.kholodilin.collector.queue;

import com.kholodilin.collector.contracts.CollectorProtocol.JobState;
import com.kholodilin.collector.contracts.CollectorProtocol.JobStatus;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/** Process-local JobQueue. No archive partitions; delivered terminals may be dropped. */
public final class InMemoryJobQueue implements JobQueue {
  private final int maxJobs;
  private final long maxQueueBytes;
  private final int maxRequestBytes;
  private final AtomicLong ids = new AtomicLong();
  private final ConcurrentHashMap<Long, Job> jobs = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<AttemptKey, Long> byAttempt = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<UUID, Long> byPublicId = new ConcurrentHashMap<>();
  private final ReentrantLock lock = new ReentrantLock();
  private final AtomicLong totalBytes = new AtomicLong();

  public InMemoryJobQueue(int maxJobs, long maxQueueBytes, int maxRequestBytes) {
    if (maxJobs < 1 || maxQueueBytes < 1024 || maxRequestBytes < 256) {
      throw new IllegalArgumentException("Invalid queue capacity");
    }
    this.maxJobs = maxJobs;
    this.maxQueueBytes = maxQueueBytes;
    this.maxRequestBytes = maxRequestBytes;
  }

  private record AttemptKey(String origin, UUID attemptId) {}

  private static final class Job {
    final long id;
    final UUID publicId;
    final String origin;
    final UUID externalJobId;
    final UUID externalAttemptId;
    final String payloadHash;
    final String payload;
    final String destination;
    final Instant deadline;
    final Instant acceptedAt;
    volatile LocalJobState state;
    volatile long generation;
    volatile int failures;
    volatile int executionNo;
    volatile String lockedBy;
    volatile Instant lockedUntil;
    volatile Instant nextAttemptAt;
    volatile UUID resultId;
    volatile UUID eventId;
    volatile String resultPayload;
    volatile int resultBytes;
    volatile long deliveryId;
    volatile DeliveryState deliveryState;
    volatile long deliveryGeneration;
    volatile String deliveryLockedBy;
    volatile Instant deliveryLockedUntil;
    volatile Instant deliveryNextAttemptAt;
    volatile DeliveryError deliveryError;
    volatile Instant deliveredAt;
    volatile Instant archivedAt;
    volatile int payloadBytes;

    Job(
        long id,
        UUID publicId,
        String origin,
        UUID jobId,
        UUID attemptId,
        String hash,
        String payload,
        String destination,
        Instant deadline,
        Instant acceptedAt,
        int payloadBytes) {
      this.id = id;
      this.publicId = publicId;
      this.origin = origin;
      this.externalJobId = jobId;
      this.externalAttemptId = attemptId;
      this.payloadHash = hash;
      this.payload = payload;
      this.destination = destination;
      this.deadline = deadline;
      this.acceptedAt = acceptedAt;
      this.state = LocalJobState.READY;
      this.nextAttemptAt = Instant.EPOCH;
      this.payloadBytes = payloadBytes;
      this.deliveryState = null;
    }
  }

  private static String owner(String value) {
    if (value == null || value.isBlank() || value.length() > 128) {
      throw new IllegalArgumentException("Invalid owner");
    }
    return value;
  }

  private static long millis(Duration duration) {
    long ms = duration.toMillis();
    if (ms <= 0 || ms > 3_600_000) {
      throw new IllegalArgumentException("Lease must be 1ms..1h");
    }
    return ms;
  }

  private static String hash(String payload, String destination, Instant deadline) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      md.update(payload.getBytes(StandardCharsets.UTF_8));
      md.update((byte) 0);
      md.update(destination.getBytes(StandardCharsets.UTF_8));
      md.update((byte) 0);
      md.update(Long.toString(deadline.toEpochMilli()).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(md.digest());
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static int utf8(String value) {
    return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
  }

  @Override
  public Accepted accept(Submission s) {
    owner(s.origin());
    owner(s.destination());
    Objects.requireNonNull(s.jobId());
    Objects.requireNonNull(s.attemptId());
    Objects.requireNonNull(s.payload());
    Objects.requireNonNull(s.deadline());
    int requestBytes = utf8(s.payload());
    if (requestBytes > maxRequestBytes) {
      throw new CapacityExceededException("Request exceeds maxRequestBytes");
    }
    String digest = hash(s.payload(), s.destination(), s.deadline());
    AttemptKey key = new AttemptKey(s.origin(), s.attemptId());
    lock.lock();
    try {
      Long existingId = byAttempt.get(key);
      if (existingId != null) {
        Job job = jobs.get(existingId);
        if (job == null) {
          throw new IllegalStateException("Missing job for attempt identity");
        }
        if (!digest.equals(job.payloadHash) || !s.jobId().equals(job.externalJobId)) {
          throw new AttemptConflictException();
        }
        return new Accepted(job.id, job.publicId, job.state.name(), true, job.acceptedAt);
      }
      if (jobs.size() >= maxJobs) {
        throw new CapacityExceededException("Queue is at maxJobs");
      }
      if (totalBytes.get() + requestBytes > maxQueueBytes) {
        throw new CapacityExceededException("Queue is at maxQueueBytes");
      }
      long id = ids.incrementAndGet();
      UUID publicId = UUID.randomUUID();
      Instant acceptedAt = Instant.now();
      Job job =
          new Job(
              id,
              publicId,
              s.origin(),
              s.jobId(),
              s.attemptId(),
              digest,
              s.payload(),
              s.destination(),
              s.deadline(),
              acceptedAt,
              requestBytes);
      jobs.put(id, job);
      byAttempt.put(key, id);
      byPublicId.put(publicId, id);
      totalBytes.addAndGet(requestBytes);
      return new Accepted(id, publicId, LocalJobState.READY.name(), false, acceptedAt);
    } finally {
      lock.unlock();
    }
  }

  private Job claimable(Instant now) {
    Job best = null;
    for (Job job : jobs.values()) {
      boolean ready = job.state.isDue() && !job.nextAttemptAt.isAfter(now);
      boolean expired =
          job.state.isRunning() && job.lockedUntil != null && !job.lockedUntil.isAfter(now);
      if (!ready && !expired) {
        continue;
      }
      if (best == null
          || job.nextAttemptAt.isBefore(best.nextAttemptAt)
          || (job.nextAttemptAt.equals(best.nextAttemptAt) && job.id < best.id)) {
        best = job;
      }
    }
    return best;
  }

  @Override
  public Optional<Lease> claim(String worker, Duration duration) {
    owner(worker);
    long ms = millis(duration);
    Instant now = Instant.now();
    lock.lock();
    try {
      Job job = claimable(now);
      if (job == null) {
        return Optional.empty();
      }
      job.state = LocalJobState.RUNNING;
      job.executionNo++;
      job.generation++;
      job.lockedBy = worker;
      job.lockedUntil = now.plusMillis(ms);
      job.nextAttemptAt = now;
      return Optional.of(
          new Lease(
              job.id,
              worker,
              job.generation,
              job.payload,
              job.deadline,
              job.origin,
              job.publicId,
              job.failures));
    } finally {
      lock.unlock();
    }
  }

  private boolean holds(Job job, Lease lease, Instant now) {
    return job != null
        && job.state.isRunning()
        && lease.owner().equals(job.lockedBy)
        && lease.generation() == job.generation
        && job.lockedUntil != null
        && job.lockedUntil.isAfter(now);
  }

  @Override
  public boolean renew(Lease lease, Duration duration) {
    long ms = millis(duration);
    Instant now = Instant.now();
    lock.lock();
    try {
      Job job = jobs.get(lease.id());
      if (!holds(job, lease, now)) {
        return false;
      }
      job.lockedUntil = now.plusMillis(ms);
      return true;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public boolean retry(Lease lease, Instant nextAttempt) {
    return retry(lease, nextAttempt, true);
  }

  @Override
  public boolean retry(Lease lease, Instant nextAttempt, boolean countFailure) {
    Instant now = Instant.now();
    lock.lock();
    try {
      Job job = jobs.get(lease.id());
      if (!holds(job, lease, now)) {
        return false;
      }
      job.state = LocalJobState.RETRY;
      job.lockedBy = null;
      job.lockedUntil = null;
      job.nextAttemptAt = nextAttempt;
      if (countFailure) {
        job.failures++;
      }
      return true;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public boolean complete(
      Lease lease, JobState terminalState, UUID resultId, UUID eventId, String payload) {
    LocalJobState local = LocalJobState.ofTerminal(terminalState);
    Objects.requireNonNull(resultId);
    Objects.requireNonNull(eventId);
    Objects.requireNonNull(payload);
    Instant now = Instant.now();
    int bytes = utf8(payload);
    lock.lock();
    try {
      Job job = jobs.get(lease.id());
      if (!holds(job, lease, now)) {
        return false;
      }
      if (totalBytes.get() + bytes > maxQueueBytes) {
        throw new CapacityExceededException("Result exceeds maxQueueBytes");
      }
      job.state = local;
      job.lockedBy = null;
      job.lockedUntil = null;
      job.resultId = resultId;
      job.eventId = eventId;
      job.resultPayload = payload;
      job.resultBytes = bytes;
      job.deliveryId = job.id;
      job.deliveryState = DeliveryState.READY;
      job.deliveryGeneration = 0;
      job.deliveryNextAttemptAt = Instant.EPOCH;
      job.deliveryLockedBy = null;
      job.deliveryLockedUntil = null;
      totalBytes.addAndGet(bytes);
      return true;
    } finally {
      lock.unlock();
    }
  }

  private Job claimableDelivery(Instant now) {
    Job best = null;
    for (Job job : jobs.values()) {
      if (job.deliveryState == null) {
        continue;
      }
      boolean ready = job.deliveryState.isDue() && !job.deliveryNextAttemptAt.isAfter(now);
      boolean expired =
          job.deliveryState.isSending()
              && job.deliveryLockedUntil != null
              && !job.deliveryLockedUntil.isAfter(now);
      if (!ready && !expired) {
        continue;
      }
      if (best == null
          || job.deliveryNextAttemptAt.isBefore(best.deliveryNextAttemptAt)
          || (job.deliveryNextAttemptAt.equals(best.deliveryNextAttemptAt)
              && job.deliveryId < best.deliveryId)) {
        best = job;
      }
    }
    return best;
  }

  @Override
  public Optional<Delivery> claimDelivery(String worker, Duration duration) {
    owner(worker);
    long ms = millis(duration);
    Instant now = Instant.now();
    lock.lock();
    try {
      Job job = claimableDelivery(now);
      if (job == null) {
        return Optional.empty();
      }
      job.deliveryState = DeliveryState.SENDING;
      job.deliveryGeneration++;
      job.deliveryLockedBy = worker;
      job.deliveryLockedUntil = now.plusMillis(ms);
      return Optional.of(
          new Delivery(
              job.deliveryId,
              worker,
              job.deliveryGeneration,
              job.destination,
              job.resultPayload,
              job.origin,
              job.id,
              job.generation));
    } finally {
      lock.unlock();
    }
  }

  private boolean holdsDelivery(Job job, Delivery d, Instant now) {
    return job != null
        && job.deliveryState != null
        && job.deliveryState.isSending()
        && d.owner().equals(job.deliveryLockedBy)
        && d.generation() == job.deliveryGeneration
        && job.deliveryLockedUntil != null
        && job.deliveryLockedUntil.isAfter(now);
  }

  @Override
  public boolean acknowledge(Delivery delivery) {
    Instant now = Instant.now();
    lock.lock();
    try {
      Job job = jobs.get(delivery.jobId());
      if (!holdsDelivery(job, delivery, now)) {
        return false;
      }
      job.deliveryState = DeliveryState.DELIVERED;
      job.deliveredAt = now;
      job.deliveryLockedBy = null;
      job.deliveryLockedUntil = null;
      return true;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public boolean retryDelivery(Delivery delivery, Instant nextAttempt) {
    Instant now = Instant.now();
    lock.lock();
    try {
      Job job = jobs.get(delivery.jobId());
      if (!holdsDelivery(job, delivery, now)) {
        return false;
      }
      job.deliveryState = DeliveryState.RETRY;
      job.deliveryNextAttemptAt = nextAttempt;
      job.deliveryLockedBy = null;
      job.deliveryLockedUntil = null;
      return true;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public boolean deadLetter(Delivery delivery, DeliveryError error) {
    Objects.requireNonNull(error);
    Instant now = Instant.now();
    lock.lock();
    try {
      Job job = jobs.get(delivery.jobId());
      if (!holdsDelivery(job, delivery, now)) {
        return false;
      }
      job.deliveryState = DeliveryState.DEAD;
      job.deliveryError = error;
      job.deliveryLockedBy = null;
      job.deliveryLockedUntil = null;
      return true;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public Optional<JobStatus> statusByAttempt(UUID attemptId, String origin) {
    Long id = byAttempt.get(new AttemptKey(origin, attemptId));
    if (id == null) {
      return Optional.empty();
    }
    Job job = jobs.get(id);
    return job == null ? Optional.empty() : status(job.publicId, origin);
  }

  @Override
  public Map<String, Object> diagnostics(String origin) {
    Instant now = Instant.now();
    long pending = 0;
    long dead = 0;
    Instant oldest = null;
    for (Job job : jobs.values()) {
      if (!origin.equals(job.origin)) {
        continue;
      }
      if (job.state.isPending()) {
        pending++;
        if (oldest == null || job.acceptedAt.isBefore(oldest)) {
          oldest = job.acceptedAt;
        }
      }
      if (job.deliveryState == DeliveryState.DEAD) {
        dead++;
      }
    }
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("pending", pending);
    values.put(
        "oldestQueueAgeSeconds",
        oldest == null ? 0L : Math.max(0L, Duration.between(oldest, now).getSeconds()));
    values.put("deadDeliveries", dead);
    return values;
  }

  @Override
  public Optional<JobStatus> status(UUID publicId, String origin) {
    Long id = byPublicId.get(publicId);
    if (id == null) {
      return Optional.empty();
    }
    Job job = jobs.get(id);
    if (job == null || !origin.equals(job.origin)) {
      return Optional.empty();
    }
    Instant updated = job.acceptedAt;
    return Optional.of(
        new JobStatus(
            1,
            job.publicId,
            job.externalJobId,
            job.externalAttemptId,
            job.state.toWire(),
            updated,
            job.resultId,
            null));
  }

  @Override
  public Optional<String> result(UUID publicId, String origin) {
    Long id = byPublicId.get(publicId);
    if (id == null) {
      return Optional.empty();
    }
    Job job = jobs.get(id);
    if (job == null || !origin.equals(job.origin) || job.resultPayload == null) {
      return Optional.empty();
    }
    return Optional.of(job.resultPayload);
  }

  @Override
  public boolean confirmDelivery(UUID publicId, String origin, UUID eventId) {
    lock.lock();
    try {
      Long id = byPublicId.get(publicId);
      if (id == null) {
        return false;
      }
      Job job = jobs.get(id);
      if (job == null
          || !origin.equals(job.origin)
          || job.eventId == null
          || !job.eventId.equals(eventId)) {
        return false;
      }
      if (job.deliveryState == null) {
        return false;
      }
      job.deliveryState = DeliveryState.DELIVERED;
      job.deliveredAt = Instant.now();
      job.deliveryLockedBy = null;
      job.deliveryLockedUntil = null;
      return true;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public boolean requeueDelivery(UUID publicId, String origin) {
    lock.lock();
    try {
      Long id = byPublicId.get(publicId);
      if (id == null) {
        return false;
      }
      Job job = jobs.get(id);
      if (job == null || !origin.equals(job.origin)) {
        return false;
      }
      if (job.deliveryState == null || !job.deliveryState.isRequeueable()) {
        return false;
      }
      job.deliveryState = DeliveryState.READY;
      job.deliveryLockedBy = null;
      job.deliveryLockedUntil = null;
      job.deliveryGeneration++;
      job.deliveryNextAttemptAt = Instant.EPOCH;
      job.deliveryError = null;
      return true;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public boolean archiveDelivered() {
    lock.lock();
    try {
      for (Job job : jobs.values()) {
        if (job.deliveryState == DeliveryState.DELIVERED && job.state.isTerminal()) {
          return remove(job);
        }
      }
      return false;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public boolean archive(long jobId, long expectedGeneration) {
    lock.lock();
    try {
      Job job = jobs.get(jobId);
      if (job == null || job.generation != expectedGeneration) {
        return false;
      }
      if (!job.state.isTerminal() || job.deliveryState != DeliveryState.DELIVERED) {
        return false;
      }
      return remove(job);
    } finally {
      lock.unlock();
    }
  }

  private boolean remove(Job job) {
    jobs.remove(job.id);
    byAttempt.remove(new AttemptKey(job.origin, job.externalAttemptId));
    byPublicId.remove(job.publicId);
    totalBytes.addAndGet(-(job.payloadBytes + job.resultBytes));
    job.archivedAt = Instant.now();
    return true;
  }

  @Override
  public Optional<Stored> find(UUID publicId) {
    Long id = byPublicId.get(publicId);
    if (id == null) {
      return Optional.empty();
    }
    Job job = jobs.get(id);
    if (job == null) {
      return Optional.empty();
    }
    String snapshot;
    if (job.resultPayload != null) {
      snapshot =
          "{\"job\":{\"id\":"
              + job.id
              + ",\"state\":\""
              + job.state.name()
              + "\",\"generation\":"
              + job.generation
              + "},\"result\":{\"public_id\":\""
              + job.resultId
              + "\",\"event_id\":\""
              + job.eventId
              + "\",\"payload\":"
              + job.resultPayload
              + "}}";
    } else {
      snapshot =
          "{\"job\":{\"id\":"
              + job.id
              + ",\"state\":\""
              + job.state.name()
              + "\",\"generation\":"
              + job.generation
              + "}}";
    }
    return Optional.of(
        new Stored(
            new Accepted(job.id, job.publicId, job.state.name(), true, job.acceptedAt),
            job.archivedAt,
            snapshot));
  }

  /** Approximate job counts keyed by state name (READY/RUNNING/…). */
  public Map<String, Long> jobCountsByState() {
    Map<String, Long> counts = new LinkedHashMap<>();
    for (Job job : jobs.values()) {
      counts.merge(job.state.name(), 1L, Long::sum);
    }
    return counts;
  }

  public long totalBytes() {
    return totalBytes.get();
  }

  public long inFlight() {
    long n = 0;
    for (Job job : jobs.values()) {
      if (job.state.isRunning() || job.deliveryState == DeliveryState.SENDING) {
        n++;
      }
    }
    return n;
  }

  public int size() {
    return jobs.size();
  }

  /** Test/helper hook to drop all process-local state. */
  public void clear() {
    lock.lock();
    try {
      jobs.clear();
      byAttempt.clear();
      byPublicId.clear();
      totalBytes.set(0);
    } finally {
      lock.unlock();
    }
  }
}
