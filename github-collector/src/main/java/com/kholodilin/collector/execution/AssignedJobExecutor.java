package com.kholodilin.collector.execution;

import com.kholodilin.collector.acceptance.CollectorMdcFilter;
import com.kholodilin.collector.contracts.CollectorProtocol.*;
import com.kholodilin.collector.credentials.*;
import com.kholodilin.collector.github.GitHubClientProperties;
import com.kholodilin.collector.queue.JobQueue;
import com.kholodilin.collector.support.RetryBackoff;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import tools.jackson.databind.json.JsonMapper;

@Slf4j
public final class AssignedJobExecutor implements AutoCloseable {
  private final JobQueue queue;
  private final AssignedTaskCollector collector;
  private final ExecutionProperties properties;
  private final GitHubClientProperties.Retry retry;
  private final JsonMapper json = JsonMapper.builder().build();
  private final ScheduledExecutorService heartbeats =
      Executors.newScheduledThreadPool(
          1,
          r -> {
            Thread t = new Thread(r, "collector-lease-heartbeat");
            t.setDaemon(true);
            return t;
          });

  public AssignedJobExecutor(
      JobQueue queue, AssignedTaskCollector collector, ExecutionProperties properties) {
    this(queue, collector, properties, new GitHubClientProperties(null, null).retry());
  }

  public AssignedJobExecutor(
      JobQueue queue,
      AssignedTaskCollector collector,
      ExecutionProperties properties,
      GitHubClientProperties.Retry retry) {
    this.queue = queue;
    this.collector = collector;
    this.properties = properties;
    this.retry = retry;
  }

  public boolean poll(String owner) {
    var pending = queue.claim(owner, properties.lease());
    if (pending.isEmpty()) return false;
    var lease = pending.get();
    AtomicBoolean live = new AtomicBoolean(true);
    var heartbeat =
        heartbeats.scheduleWithFixedDelay(
            () -> {
              try {
                if (!queue.renew(lease, properties.lease())) live.set(false);
              } catch (RuntimeException e) {
                live.set(false);
              }
            },
            properties.lease().toMillis() / 3,
            properties.lease().toMillis() / 3,
            TimeUnit.MILLISECONDS);
    JobRequest job;
    try {
      job = json.readValue(lease.payload(), JobRequest.class);
    } catch (RuntimeException invalid) {
      log.warn("Invalid job payload on claim publicId={}; deferring retry", lease.publicId());
      queue.retry(lease, Instant.now().plusSeconds(60), true);
      heartbeat.cancel(false);
      return true;
    }
    MDC.put(CollectorMdcFilter.ATTEMPT, job.attemptId().toString());
    CollectorMdcFilter.bindJob(job.jobId().toString(), job.type().name());
    try {
      log.info("Collect start type={} repository={}", job.type(), job.repository().githubId());
      if (!job.deadlineAt().isAfter(Instant.now())) {
        finish(
            lease,
            job,
            null,
            new Failure(ErrorCode.DEADLINE_EXCEEDED, false, "Assignment deadline exceeded", null),
            live);
        return true;
      }
      Snapshot snapshot = collector.collect(lease, job);
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
      if (payload.getBytes(StandardCharsets.UTF_8).length > properties.maximumPayloadBytes())
        throw new ObservationStore.PayloadTooLarge();
      queue.complete(lease, JobState.SUCCEEDED, delivery.resultId(), delivery.eventId(), payload);
      log.info("Collect succeeded type={}", job.type());
    } catch (CredentialedGitHubRequest.Failure error) {
      if ((error.status() == 429 || error.status() == 403) && error.retryAt() != null) {
        Instant when = later(error.retryAt());
        log.info("GitHub rate-limit/defer status={} retryAt={}", error.status(), when);
        queue.retry(lease, when, false);
      } else if (error.status() == 413)
        finish(
            lease,
            job,
            null,
            new Failure(
                ErrorCode.INVALID_REQUEST,
                false,
                "Source response exceeds payload limit; narrow the assignment",
                null),
            live);
      else if (error.status() == 401 || error.status() == 403 || error.status() == 404)
        finish(
            lease,
            job,
            null,
            new Failure(
                error.status() == 401
                    ? ErrorCode.AUTHENTICATION_FAILED
                    : error.status() == 404
                        ? ErrorCode.SOURCE_NOT_FOUND
                        : ErrorCode.PERMISSION_DENIED,
                false,
                "GitHub rejected the assigned collection",
                null),
            live);
      else retryOrFail(lease, job, live);
    } catch (ObservationStore.PayloadTooLarge tooLarge) {
      finish(
          lease,
          job,
          null,
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
      MDC.remove(CollectorMdcFilter.ATTEMPT);
      MDC.remove(CollectorMdcFilter.JOB);
      MDC.remove(CollectorMdcFilter.TASK);
    }
    return true;
  }

  private Instant later(Instant value) {
    Instant now = Instant.now();
    return value.isAfter(now) ? value : now.plusSeconds(1);
  }

  private void retryOrFail(JobQueue.Lease lease, JobRequest job, AtomicBoolean live) {
    if (!live.get()) return;
    int failures = lease.failures() + 1;
    if (failures >= Math.min(properties.maximumFailures(), retry.maxAttempts()))
      finish(
          lease,
          job,
          null,
          new Failure(
              ErrorCode.SOURCE_UNAVAILABLE, false, "Collection failed after bounded retries", null),
          live);
    else {
      Duration wait =
          RetryBackoff.delay(
              lease.failures(),
              retry.initialDelay(),
              retry.maxDelay(),
              retry.multiplier(),
              retry.jitter());
      log.info("Collect technical retry failures={} delay={}", failures, wait);
      queue.retry(lease, Instant.now().plus(wait), true);
    }
  }

  private void finish(
      JobQueue.Lease lease,
      JobRequest job,
      Snapshot snapshot,
      Failure failure,
      AtomicBoolean live) {
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
        JobState.FAILED,
        delivery.resultId(),
        delivery.eventId(),
        json.writeValueAsString(delivery));
  }

  @Override
  public void close() {
    heartbeats.shutdownNow();
  }
}
