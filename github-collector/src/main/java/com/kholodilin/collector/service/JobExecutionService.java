package com.kholodilin.collector.service;

import com.kholodilin.collector.contracts.CollectorProtocol.*;
import com.kholodilin.collector.logging.CollectorMdc;
import com.kholodilin.collector.model.CollectionContext;
import com.kholodilin.collector.model.ExecutionSettings;
import com.kholodilin.collector.model.GitHubAuthenticationFailedException;
import com.kholodilin.collector.model.GitHubNotFoundException;
import com.kholodilin.collector.model.GitHubPermissionDeniedException;
import com.kholodilin.collector.model.GitHubRateLimitedException;
import com.kholodilin.collector.model.GitHubResponseTooLargeException;
import com.kholodilin.collector.model.LocalJobState;
import com.kholodilin.collector.model.PayloadTooLarge;
import com.kholodilin.collector.model.RetryBackoff;
import com.kholodilin.collector.model.RetrySettings;
import com.kholodilin.collector.repository.JobQueue;
import com.kholodilin.collector.service.collection.AssignedTaskCollectionService;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

/**
 * Executes one claimed assignment: heartbeat the lease, collect, and decide retry / complete / fail
 * from semantic collection errors. No HTTP status code is interpreted here.
 */
@Slf4j
@RequiredArgsConstructor
public final class JobExecutionService implements AutoCloseable {
  private final JobQueue queue;
  private final AssignedTaskCollectionService collector;
  private final ExecutionSettings settings;
  private final RetrySettings githubRetry;
  private final JsonMapper json;
  private final Clock clock;
  private final ScheduledExecutorService heartbeats =
      Executors.newScheduledThreadPool(
          1,
          r -> {
            Thread t = new Thread(r, "collector-lease-heartbeat");
            t.setDaemon(true);
            return t;
          });

  public boolean poll(String owner) {
    var pending = queue.claim(owner, settings.lease());
    if (pending.isEmpty()) return false;
    var lease = pending.get();
    AtomicBoolean live = new AtomicBoolean(true);
    var heartbeat =
        heartbeats.scheduleWithFixedDelay(
            () -> {
              try {
                if (!queue.renew(lease, settings.lease())) live.set(false);
              } catch (RuntimeException e) {
                log.warn(
                    "Lease heartbeat failed publicId={} generation={}",
                    lease.publicId(),
                    lease.generation(),
                    e);
                live.set(false);
              }
            },
            settings.lease().toMillis() / 3,
            settings.lease().toMillis() / 3,
            TimeUnit.MILLISECONDS);
    JobRequest job;
    try {
      job = json.readValue(lease.payload(), JobRequest.class);
    } catch (RuntimeException invalid) {
      log.warn("Invalid job payload on claim publicId={}; deferring retry", lease.publicId());
      queue.retry(lease, clock.instant().plusSeconds(60), true);
      heartbeat.cancel(false);
      return true;
    }
    CollectorMdc.bindAttempt(job.attemptId().toString());
    CollectorMdc.bindJob(job.jobId().toString(), job.type().name());
    try {
      log.info("Collect start type={} repository={}", job.type(), job.repository().githubId());
      if (!job.deadlineAt().isAfter(clock.instant())) {
        finish(
            lease,
            job,
            new Failure(ErrorCode.DEADLINE_EXCEEDED, false, "Assignment deadline exceeded", null),
            live);
        return true;
      }
      Snapshot snapshot = collector.collect(new CollectionContext(lease.origin(), lease.id()), job);
      if (!live.get()) {
        log.info("Collect aborted after lease loss type={}", job.type());
        return true;
      }
      var delivery =
          new ResultDelivery(
              1,
              UUID.randomUUID(),
              UUID.randomUUID(),
              lease.publicId(),
              job.jobId(),
              job.attemptId(),
              job.type(),
              job.repository().githubId(),
              JobState.SUCCEEDED,
              snapshot,
              null);
      String payload = json.writeValueAsString(delivery);
      if (payload.getBytes(StandardCharsets.UTF_8).length > settings.maximumPayloadBytes())
        throw new PayloadTooLarge();
      queue.complete(
          lease, LocalJobState.SUCCEEDED, delivery.resultId(), delivery.eventId(), payload);
      log.info("Collect succeeded type={}", job.type());
    } catch (GitHubRateLimitedException limited) {
      Instant when = later(limited.retryAt());
      log.info("GitHub rate-limit/defer retryAt={}", when);
      queue.retry(lease, when, false);
    } catch (GitHubResponseTooLargeException tooLarge) {
      finish(
          lease,
          job,
          new Failure(
              ErrorCode.INVALID_REQUEST,
              false,
              "Source response exceeds payload limit; narrow the assignment",
              null),
          live);
    } catch (GitHubAuthenticationFailedException rejected) {
      reject(lease, job, ErrorCode.AUTHENTICATION_FAILED, live);
    } catch (GitHubNotFoundException rejected) {
      reject(lease, job, ErrorCode.SOURCE_NOT_FOUND, live);
    } catch (GitHubPermissionDeniedException rejected) {
      reject(lease, job, ErrorCode.PERMISSION_DENIED, live);
    } catch (PayloadTooLarge tooLarge) {
      finish(
          lease,
          job,
          new Failure(
              ErrorCode.INVALID_REQUEST,
              false,
              "Collection exceeds payload/page limits; narrow or split the assigned work",
              null),
          live);
    } catch (RuntimeException failure) {
      log.debug("Technical collect failure type={}: {}", job.type(), failure.toString());
      retryOrFail(lease, job, live);
    } finally {
      heartbeat.cancel(false);
      CollectorMdc.clear();
    }
    return true;
  }

  private Instant later(Instant value) {
    Instant now = clock.instant();
    return value.isAfter(now) ? value : now.plusSeconds(1);
  }

  private void reject(JobQueue.Lease lease, JobRequest job, ErrorCode code, AtomicBoolean live) {
    finish(
        lease,
        job,
        new Failure(code, false, "GitHub rejected the assigned collection", null),
        live);
  }

  private void retryOrFail(JobQueue.Lease lease, JobRequest job, AtomicBoolean live) {
    if (!live.get()) return;
    int failures = lease.failures() + 1;
    if (failures >= Math.min(settings.maximumFailures(), githubRetry.maxAttempts()))
      finish(
          lease,
          job,
          new Failure(
              ErrorCode.SOURCE_UNAVAILABLE, false, "Collection failed after bounded retries", null),
          live);
    else {
      Duration wait =
          RetryBackoff.delay(
              lease.failures(),
              githubRetry.initialDelay(),
              githubRetry.maxDelay(),
              githubRetry.multiplier(),
              githubRetry.jitter());
      log.info("Collect technical retry failures={} delay={}", failures, wait);
      queue.retry(lease, clock.instant().plus(wait), true);
    }
  }

  private void finish(JobQueue.Lease lease, JobRequest job, Failure failure, AtomicBoolean live) {
    if (!live.get()) return;
    log.info("Collect failed type={} code={}", job.type(), failure.code());
    var delivery =
        new ResultDelivery(
            1,
            UUID.randomUUID(),
            UUID.randomUUID(),
            lease.publicId(),
            job.jobId(),
            job.attemptId(),
            job.type(),
            job.repository().githubId(),
            JobState.FAILED,
            null,
            failure);
    queue.complete(
        lease,
        LocalJobState.FAILED,
        delivery.resultId(),
        delivery.eventId(),
        json.writeValueAsString(delivery));
  }

  @Override
  public void close() {
    heartbeats.shutdownNow();
  }
}
