package com.kholodilin.collector.delivery;

import com.kholodilin.collector.credentials.CollectorCredentialProperties;
import com.kholodilin.collector.queue.DeliveryError;
import com.kholodilin.collector.queue.JobQueue;
import com.kholodilin.collector.support.RetryBackoff;
import java.io.IOException;
import java.time.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Only terminal immutable results are delivered; retrying transport never calls a source collector.
 */
@Slf4j
public final class ResultDeliveryWorker {
  private final JobQueue queue;
  private final DeliveryProperties properties;
  private final CollectorCredentialProperties credentials;
  private final RestClient client;

  public ResultDeliveryWorker(
      JobQueue queue, DeliveryProperties properties, CollectorCredentialProperties credentials) {
    this.queue = queue;
    this.properties = properties;
    this.credentials = credentials;
    if (properties
            .lease()
            .compareTo(credentials.connectTimeout().plus(credentials.readTimeout()).plusSeconds(5))
        <= 0) throw new IllegalArgumentException("Delivery lease must exceed callback timeouts");
    var factory =
        new SimpleClientHttpRequestFactory() {
          @Override
          protected void prepareConnection(java.net.HttpURLConnection c, String method)
              throws IOException {
            super.prepareConnection(c, method);
            c.setInstanceFollowRedirects(false);
          }
        };
    factory.setConnectTimeout(credentials.connectTimeout());
    factory.setReadTimeout(credentials.readTimeout());
    client = RestClient.builder().requestFactory(factory).build();
  }

  public boolean poll(String owner) {
    var candidate = queue.claimDelivery(owner, properties.lease());
    if (candidate.isEmpty()) return queue.archiveDelivered();
    var delivery = candidate.get();
    var destination =
        properties.destinations().stream()
            .filter(
                d -> d.origin().equals(delivery.origin()) && d.ref().equals(delivery.destination()))
            .findFirst();
    var authority =
        credentials.origins().stream()
            .filter(o -> o.origin().equals(delivery.origin()))
            .findFirst();
    if (destination.isEmpty() || authority.isEmpty()) {
      log.warn(
          "Delivery dead-letter: unknown destination/origin ref={} origin={}",
          delivery.destination(),
          delivery.origin());
      queue.deadLetter(delivery, DeliveryError.DESTINATION_UNAVAILABLE);
      return true;
    }
    try {
      int response =
          client
              .post()
              .uri(destination.get().endpoint())
              .headers(h -> h.setBearerAuth(authority.get().serviceToken()))
              .contentType(MediaType.APPLICATION_JSON)
              .body(delivery.payload())
              .exchange((request, result) -> result.getStatusCode().value());
      if (response == 204) {
        if (queue.acknowledge(delivery)) queue.archive(delivery.jobId(), delivery.jobGeneration());
        log.info("Delivered result to RGM ref={} status={}", delivery.destination(), response);
      } else if (response == 408 || response == 429 || response >= 500) {
        log.info("Delivery retryable HTTP {} ref={}", response, delivery.destination());
        retry(delivery);
      } else {
        log.warn("Delivery rejected HTTP {} ref={}", response, delivery.destination());
        queue.deadLetter(delivery, DeliveryError.REJECTED);
      }
    } catch (RuntimeException error) {
      log.info("Delivery transport failure ref={}: {}", delivery.destination(), error.toString());
      retry(delivery);
    }
    return true;
  }

  private void retry(JobQueue.Delivery delivery) {
    var policy = properties.retry();
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
