package com.kholodilin.collector.service;

import com.kholodilin.collector.integration.delivery.DeliveryOutcome;
import com.kholodilin.collector.integration.delivery.ResultDeliveryClient;
import com.kholodilin.collector.model.DeliveryError;
import com.kholodilin.collector.model.DeliverySettings;
import com.kholodilin.collector.model.RetryBackoff;
import com.kholodilin.collector.repository.JobQueue;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Only terminal immutable results are delivered; retrying transport never calls a source collector.
 * Claims one delivery, hands it to the client and decides acknowledge / retry / dead-letter from
 * the client's outcome.
 */
@Slf4j
@RequiredArgsConstructor
public final class ResultDeliveryService {
  private final JobQueue queue;
  private final DeliverySettings settings;
  private final ResultDeliveryClient client;

  public boolean poll(String owner) {
    var candidate = queue.claimDelivery(owner, settings.lease());
    if (candidate.isEmpty()) return queue.archiveDelivered();
    var delivery = candidate.get();
    DeliveryOutcome outcome =
        client.deliver(delivery.origin(), delivery.destination(), delivery.payload());
    switch (outcome) {
      case DELIVERED -> {
        if (queue.acknowledge(delivery)) queue.archive(delivery.jobId(), delivery.jobGeneration());
      }
      case RETRYABLE -> retry(delivery);
      case REJECTED -> queue.deadLetter(delivery, DeliveryError.REJECTED);
      case DESTINATION_UNAVAILABLE ->
          queue.deadLetter(delivery, DeliveryError.DESTINATION_UNAVAILABLE);
    }
    return true;
  }

  private void retry(JobQueue.Delivery delivery) {
    var policy = settings.retry();
    if (delivery.generation() >= policy.maxAttempts()) {
      log.warn(
          "Delivery retry exhausted generation={} ref={}",
          delivery.generation(),
          delivery.destination());
      queue.deadLetter(delivery, DeliveryError.RETRY_EXHAUSTED);
    } else {
      Duration wait =
          RetryBackoff.delay(
              Math.max(0, (int) delivery.generation() - 1),
              policy.initialDelay(),
              policy.maxDelay(),
              policy.multiplier(),
              policy.jitter());
      log.debug("Delivery retry generation={} delay={}", delivery.generation(), wait);
      queue.retryDelivery(delivery, Instant.now().plus(wait));
    }
  }
}
