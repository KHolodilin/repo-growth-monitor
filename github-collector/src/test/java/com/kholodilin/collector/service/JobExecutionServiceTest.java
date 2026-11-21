package com.kholodilin.collector.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.kholodilin.collector.contracts.CollectorProtocol.*;
import com.kholodilin.collector.model.*;
import com.kholodilin.collector.repository.JobQueue;
import java.net.URI;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

class JobExecutionServiceTest {
  JobQueue queue = mock(JobQueue.class);
  AssignedTaskCollectionService collector = mock(AssignedTaskCollectionService.class);
  JsonMapper json = JsonMapper.builder().build();
  static final RetrySettings RETRY =
      new RetrySettings(5, Duration.ofSeconds(5), Duration.ofMinutes(5), 2.0, 0.2);
  ExecutionSettings properties = settings(Duration.ofMinutes(1), Duration.ofSeconds(10));

  static ExecutionSettings settings(Duration lease, Duration requestTimeout) {
    return new ExecutionSettings(
        URI.create("https://api.github.com"),
        lease,
        Duration.ofMillis(250),
        requestTimeout,
        5,
        5,
        5 * 1024 * 1024);
  }

  JobRequest job =
      new JobRequest(
          1,
          UUID.randomUUID(),
          UUID.randomUUID(),
          UUID.randomUUID(),
          TaskType.STATS,
          new RepositoryTarget("202", "owner", "repo"),
          LocalDate.now(),
          1,
          Parameters.repository(),
          "rgm-results",
          Instant.now().plusSeconds(3600));
  JobQueue.Lease lease =
      new JobQueue.Lease(
          1,
          "worker",
          1,
          json.writeValueAsString(job),
          job.deadlineAt(),
          "rgm",
          UUID.randomUUID(),
          0);

  @BeforeEach
  void setup() {
    when(queue.claim(anyString(), any())).thenReturn(Optional.of(lease));
    when(queue.renew(any(), any())).thenReturn(true);
  }

  @Test
  void completionStoresOneImmutableDeliveryEnvelope() {
    when(collector.collect(any(CollectionContext.class), eq(job)))
        .thenReturn(
            new Snapshot(
                Instant.now(),
                true,
                Map.of(Metric.STARS, 0L),
                null,
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
                Map.of(),
                List.of()));
    try (var executor = new JobExecutionService(queue, collector, properties, RETRY, json)) {
      assertThat(executor.poll("worker")).isTrue();
    }
    var payload = ArgumentCaptor.forClass(String.class);
    verify(queue).complete(eq(lease), eq(LocalJobState.SUCCEEDED), any(), any(), payload.capture());
    var result = json.readValue(payload.getValue(), ResultDelivery.class);
    assertThat(result.collectorJobId()).isEqualTo(lease.publicId());
    assertThat(result.jobId()).isEqualTo(job.jobId());
    assertThat(result.snapshot().metrics()).containsEntry(Metric.STARS, 0L);
  }

  @Test
  void quotaWaitingDoesNotSpendTechnicalRetryBudget() {
    Instant next = Instant.now().plusSeconds(60);
    when(collector.collect(any(CollectionContext.class), eq(job)))
        .thenThrow(new GitHubRateLimitedException(next));
    try (var executor = new JobExecutionService(queue, collector, properties, RETRY, json)) {
      executor.poll("worker");
    }
    verify(queue).retry(lease, next, false);
    verify(queue, never()).complete(any(), any(), any(), any(), anyString());
  }

  @Test
  void permanentPermissionFailureDoesNotRepeatGitHubCollection() {
    when(collector.collect(any(CollectionContext.class), eq(job)))
        .thenThrow(new GitHubPermissionDeniedException());
    try (var executor = new JobExecutionService(queue, collector, properties, RETRY, json)) {
      executor.poll("worker");
    }
    verify(queue)
        .complete(eq(lease), eq(LocalJobState.FAILED), any(), any(), contains("PERMISSION_DENIED"));
    verify(queue, never()).retry(any(), any(), anyBoolean());
  }

  @Test
  void technicalFailuresUseBoundedRetriesAndSanitizedPayloads() {
    when(collector.collect(any(CollectionContext.class), eq(job)))
        .thenThrow(new RuntimeException("secret-must-not-be-saved"));
    try (var executor = new JobExecutionService(queue, collector, properties, RETRY, json)) {
      executor.poll("worker");
    }
    verify(queue).retry(eq(lease), any(), eq(true));
    var exhausted =
        new JobQueue.Lease(
            lease.id(),
            lease.owner(),
            5,
            lease.payload(),
            lease.deadline(),
            lease.origin(),
            lease.publicId(),
            4);
    when(queue.claim(anyString(), any())).thenReturn(Optional.of(exhausted));
    when(collector.collect(any(CollectionContext.class), eq(job)))
        .thenThrow(new RuntimeException("secret-must-not-be-saved"));
    try (var executor = new JobExecutionService(queue, collector, properties, RETRY, json)) {
      executor.poll("worker");
    }
    var payload = ArgumentCaptor.forClass(String.class);
    verify(queue)
        .complete(eq(exhausted), eq(LocalJobState.FAILED), any(), any(), payload.capture());
    assertThat(payload.getValue()).doesNotContain("secret-must-not-be-saved");
  }

  @Test
  void longWorkRenewsLeaseAndLostOwnershipPreventsCompletion() throws Exception {
    var shortLease = settings(Duration.ofMillis(90), Duration.ofMillis(20));
    var renewed = new java.util.concurrent.CountDownLatch(2);
    when(queue.renew(any(), any()))
        .thenAnswer(invocation -> {
          renewed.countDown();
          return true;
        });
    when(collector.collect(any(CollectionContext.class), eq(job)))
        .thenAnswer(invocation -> {
          assertThat(renewed.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
          return new Snapshot(
              Instant.now(),
              true,
              Map.of(),
              null,
              List.of(),
              List.of(),
              List.of(),
              null,
              null,
              Map.of(),
              List.of());
        });
    try (var executor = new JobExecutionService(queue, collector, shortLease, RETRY, json)) {
      executor.poll("worker");
    }
    verify(queue, atLeast(2)).renew(lease, shortLease.lease());
    verify(queue).complete(eq(lease), eq(LocalJobState.SUCCEEDED), any(), any(), anyString());
    reset(queue, collector);
    when(queue.claim(anyString(), any())).thenReturn(Optional.of(lease));
    var fenced = new java.util.concurrent.CountDownLatch(1);
    when(queue.renew(any(), any()))
        .thenAnswer(invocation -> {
          fenced.countDown();
          return false;
        });
    when(collector.collect(any(CollectionContext.class), eq(job)))
        .thenAnswer(invocation -> {
          assertThat(fenced.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
          Thread.sleep(20);
          return new Snapshot(
              Instant.now(),
              true,
              Map.of(),
              null,
              List.of(),
              List.of(),
              List.of(),
              null,
              null,
              Map.of(),
              List.of());
        });
    try (var executor = new JobExecutionService(queue, collector, shortLease, RETRY, json)) {
      executor.poll("worker");
    }
    verify(queue, never()).complete(any(), any(), any(), any(), anyString());
  }

  @Test
  void permanentSourceFailuresMapToSemanticErrorCodesWithoutRetry() {
    Map<RuntimeException, String> expected = new LinkedHashMap<>();
    expected.put(new GitHubAuthenticationFailedException(), "AUTHENTICATION_FAILED");
    expected.put(new GitHubNotFoundException(), "SOURCE_NOT_FOUND");
    expected.put(new GitHubPermissionDeniedException(), "PERMISSION_DENIED");
    expected.forEach((error, code) -> {
      reset(queue, collector);
      when(queue.claim(anyString(), any())).thenReturn(Optional.of(lease));
      when(queue.renew(any(), any())).thenReturn(true);
      when(collector.collect(any(CollectionContext.class), eq(job))).thenThrow(error);
      try (var executor = new JobExecutionService(queue, collector, properties, RETRY, json)) {
        executor.poll("worker");
      }
      verify(queue).complete(eq(lease), eq(LocalJobState.FAILED), any(), any(), contains(code));
      verify(queue, never()).retry(any(), any(), anyBoolean());
    });
  }

  @Test
  void oversizedSourceResponseAndOversizedCollectionBecomeInvalidRequest() {
    for (RuntimeException error :
        List.of(new GitHubResponseTooLargeException(), new PayloadTooLarge())) {
      reset(queue, collector);
      when(queue.claim(anyString(), any())).thenReturn(Optional.of(lease));
      when(queue.renew(any(), any())).thenReturn(true);
      when(collector.collect(any(CollectionContext.class), eq(job))).thenThrow(error);
      try (var executor = new JobExecutionService(queue, collector, properties, RETRY, json)) {
        executor.poll("worker");
      }
      verify(queue)
          .complete(eq(lease), eq(LocalJobState.FAILED), any(), any(), contains("INVALID_REQUEST"));
      verify(queue, never()).retry(any(), any(), anyBoolean());
    }
  }

  @Test
  void temporarySourceOutageSpendsTheTechnicalRetryBudget() {
    when(collector.collect(any(CollectionContext.class), eq(job)))
        .thenThrow(new GitHubSourceUnavailableException());
    try (var executor = new JobExecutionService(queue, collector, properties, RETRY, json)) {
      executor.poll("worker");
    }
    verify(queue).retry(eq(lease), any(), eq(true));
    verify(queue, never()).complete(any(), any(), any(), any(), anyString());
  }

  @Test
  void rateLimitDeferralInThePastIsPushedToTheNextSecond() {
    when(collector.collect(any(CollectionContext.class), eq(job)))
        .thenThrow(new GitHubRateLimitedException(Instant.now().minusSeconds(30)));
    try (var executor = new JobExecutionService(queue, collector, properties, RETRY, json)) {
      executor.poll("worker");
    }
    var next = ArgumentCaptor.forClass(Instant.class);
    verify(queue).retry(eq(lease), next.capture(), eq(false));
    assertThat(next.getValue()).isAfter(Instant.now().minusSeconds(1));
  }

  @Test
  void expiredDeadlineFailsWithoutCollecting() {
    var late =
        new JobRequest(
            1,
            job.requestId(),
            job.jobId(),
            job.attemptId(),
            job.type(),
            job.repository(),
            job.businessDate(),
            1,
            job.parameters(),
            job.destinationRef(),
            Instant.now().minusSeconds(1));
    var expired =
        new JobQueue.Lease(
            1,
            "worker",
            1,
            json.writeValueAsString(late),
            late.deadlineAt(),
            "rgm",
            UUID.randomUUID(),
            0);
    when(queue.claim(anyString(), any())).thenReturn(Optional.of(expired));
    try (var executor = new JobExecutionService(queue, collector, properties, RETRY, json)) {
      executor.poll("worker");
    }
    verify(collector, never()).collect(any(), any());
    verify(queue)
        .complete(
            eq(expired), eq(LocalJobState.FAILED), any(), any(), contains("DEADLINE_EXCEEDED"));
  }

  @Test
  void unreadablePayloadIsDeferredNotFailedOrCollected() {
    var broken =
        new JobQueue.Lease(1, "worker", 1, "not-json", Instant.now(), "rgm", UUID.randomUUID(), 0);
    when(queue.claim(anyString(), any())).thenReturn(Optional.of(broken));
    try (var executor = new JobExecutionService(queue, collector, properties, RETRY, json)) {
      assertThat(executor.poll("worker")).isTrue();
    }
    verify(queue).retry(eq(broken), any(), eq(true));
    verifyNoInteractions(collector);
  }

  @Test
  void emptyQueueReportsNoWork() {
    when(queue.claim(anyString(), any())).thenReturn(Optional.empty());
    try (var executor = new JobExecutionService(queue, collector, properties, RETRY, json)) {
      assertThat(executor.poll("worker")).isFalse();
    }
  }
}
