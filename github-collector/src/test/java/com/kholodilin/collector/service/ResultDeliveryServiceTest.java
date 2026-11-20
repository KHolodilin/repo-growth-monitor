package com.kholodilin.collector.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kholodilin.collector.integration.delivery.DeliveryOutcome;
import com.kholodilin.collector.integration.delivery.ResultDeliveryClient;
import com.kholodilin.collector.model.DeliveryError;
import com.kholodilin.collector.model.DeliverySettings;
import com.kholodilin.collector.model.RetrySettings;
import com.kholodilin.collector.repository.JobQueue;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ResultDeliveryServiceTest {
  private static final Duration LEASE = Duration.ofSeconds(60);

  private JobQueue queue;
  private ResultDeliveryClient client;
  private ResultDeliveryService service;

  @BeforeEach
  void setup() {
    queue = mock(JobQueue.class);
    client = mock(ResultDeliveryClient.class);
    var settings =
        new DeliverySettings(
            LEASE,
            Duration.ofMillis(10),
            new RetrySettings(3, Duration.ofSeconds(1), Duration.ofSeconds(10), 2.0, 0.0),
            List.of());
    service = new ResultDeliveryService(queue, settings, client);
  }

  private JobQueue.Delivery delivery(long generation) {
    return new JobQueue.Delivery(1, "worker", generation, "rgm-results", "{}", "rgm", 7, 2);
  }

  private void deliveryOutcome(JobQueue.Delivery delivery, DeliveryOutcome outcome) {
    when(queue.claimDelivery("worker", LEASE)).thenReturn(Optional.of(delivery));
    when(client.deliver("rgm", "rgm-results", "{}")).thenReturn(outcome);
  }

  @Test
  void emptyQueueOnlyArchivesAndNeverCallsTheClient() {
    when(queue.claimDelivery("worker", LEASE)).thenReturn(Optional.empty());
    when(queue.archiveDelivered()).thenReturn(true);

    assertThat(service.poll("worker")).isTrue();

    verify(client, never()).deliver(anyString(), anyString(), anyString());
  }

  @Test
  void deliveredResultIsAcknowledgedThenArchivedWithItsJobGeneration() {
    var delivery = delivery(1);
    deliveryOutcome(delivery, DeliveryOutcome.DELIVERED);
    when(queue.acknowledge(delivery)).thenReturn(true);

    assertThat(service.poll("worker")).isTrue();

    verify(queue).archive(7, 2);
  }

  @Test
  void deliveredResultIsNotArchivedWhenTheAcknowledgementLostTheLease() {
    var delivery = delivery(1);
    deliveryOutcome(delivery, DeliveryOutcome.DELIVERED);
    when(queue.acknowledge(delivery)).thenReturn(false);

    service.poll("worker");

    verify(queue, never()).archive(7, 2);
  }

  @Test
  void retryableOutcomeSchedulesABackoffRetryInTheFuture() {
    var delivery = delivery(1);
    deliveryOutcome(delivery, DeliveryOutcome.RETRYABLE);
    Instant before = Instant.now();

    service.poll("worker");

    var next = ArgumentCaptor.forClass(Instant.class);
    verify(queue).retryDelivery(any(), next.capture());
    assertThat(next.getValue()).isAfterOrEqualTo(before.plusMillis(500));
    verify(queue, never()).deadLetter(any(), any());
  }

  @Test
  void retryableOutcomeDeadLettersOnceTheAttemptsAreExhausted() {
    var delivery = delivery(3);
    deliveryOutcome(delivery, DeliveryOutcome.RETRYABLE);

    service.poll("worker");

    verify(queue).deadLetter(delivery, DeliveryError.RETRY_EXHAUSTED);
    verify(queue, never()).retryDelivery(any(), any());
  }

  @Test
  void rejectedOutcomeDeadLettersImmediately() {
    var delivery = delivery(1);
    deliveryOutcome(delivery, DeliveryOutcome.REJECTED);

    service.poll("worker");

    verify(queue).deadLetter(delivery, DeliveryError.REJECTED);
    verify(queue, never()).retryDelivery(any(), any());
  }

  @Test
  void unknownDestinationDeadLettersAsUnavailable() {
    var delivery = delivery(1);
    deliveryOutcome(delivery, DeliveryOutcome.DESTINATION_UNAVAILABLE);

    service.poll("worker");

    verify(queue).deadLetter(delivery, DeliveryError.DESTINATION_UNAVAILABLE);
  }
}
