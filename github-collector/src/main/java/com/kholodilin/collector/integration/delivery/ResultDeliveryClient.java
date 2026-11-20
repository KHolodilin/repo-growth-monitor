package com.kholodilin.collector.integration.delivery;

import com.kholodilin.collector.integration.HttpClients;
import com.kholodilin.collector.model.CredentialSettings;
import com.kholodilin.collector.model.DeliverySettings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * HTTP POST of one immutable terminal result to a trusted RGM destination. Redirects are never
 * followed; the service credential is sent only to the configured endpoint. Transport details stay
 * here: callers receive a {@link DeliveryOutcome}.
 */
@Slf4j
@RequiredArgsConstructor
public final class ResultDeliveryClient {
  private final DeliverySettings delivery;
  private final CredentialSettings credentials;
  private final RestClient client;

  public static RestClient restClient(CredentialSettings credentials) {
    return HttpClients.noRedirect(credentials.connectTimeout(), credentials.readTimeout());
  }

  public DeliveryOutcome deliver(String origin, String destinationRef, String payload) {
    var destination =
        delivery.destinations().stream()
            .filter(d -> d.origin().equals(origin) && d.ref().equals(destinationRef))
            .findFirst();
    var authority =
        credentials.origins().stream().filter(o -> o.origin().equals(origin)).findFirst();
    if (destination.isEmpty() || authority.isEmpty()) {
      log.warn(
          "Delivery destination unavailable: unknown destination/origin ref={} origin={}",
          destinationRef,
          origin);
      return DeliveryOutcome.DESTINATION_UNAVAILABLE;
    }
    try {
      int response =
          client
              .post()
              .uri(destination.get().endpoint())
              .headers(h -> h.setBearerAuth(authority.get().serviceToken()))
              .contentType(MediaType.APPLICATION_JSON)
              .body(payload)
              .exchange((request, result) -> result.getStatusCode().value());
      return outcome(response, destinationRef);
    } catch (RuntimeException error) {
      log.info("Delivery transport failure ref={}: {}", destinationRef, error.toString());
      return DeliveryOutcome.RETRYABLE;
    }
  }

  static DeliveryOutcome outcome(int status, String destinationRef) {
    if (status == 204) {
      log.info("Delivered result to RGM ref={} status={}", destinationRef, status);
      return DeliveryOutcome.DELIVERED;
    }
    if (status == 408 || status == 429 || status >= 500) {
      log.info("Delivery retryable HTTP {} ref={}", status, destinationRef);
      return DeliveryOutcome.RETRYABLE;
    }
    log.warn("Delivery rejected HTTP {} ref={}", status, destinationRef);
    return DeliveryOutcome.REJECTED;
  }
}
