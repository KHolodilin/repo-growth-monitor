package com.kholodilin.repogrowth.collection.integration;

import com.kholodilin.collector.contracts.CollectorHttpHeaders;
import com.kholodilin.collector.contracts.CollectorProtocol.*;
import com.kholodilin.repogrowth.collection.configuration.OrchestrationProperties;
import com.kholodilin.repogrowth.collection.model.DispatchClaim;
import com.kholodilin.repogrowth.collection.model.DispatchError;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;

/**
 * HTTP client for collector job dispatch. Translates transport failures into typed outcomes so
 * {@code collection.service} never depends on RestClient / HttpStatus.
 */
@Component
@ConditionalOnProperty(name = "rgm.orchestration.enabled", havingValue = "true")
public class CollectorTransport {
  public sealed interface SendResult {
    record Accepted(JobAccepted accepted) implements SendResult {}

    record Failed(DispatchError error, boolean retry, Duration retryAfter) implements SendResult {}
  }

  private final OrchestrationProperties properties;
  private final RestClient client;

  public CollectorTransport(OrchestrationProperties properties) {
    this.properties = properties;
    var factory =
        new SimpleClientHttpRequestFactory() {
          @Override
          protected void prepareConnection(java.net.HttpURLConnection connection, String method)
              throws java.io.IOException {
            super.prepareConnection(connection, method);
            connection.setInstanceFollowRedirects(false);
          }
        };
    factory.setConnectTimeout(properties.connectTimeout());
    factory.setReadTimeout(properties.readTimeout());
    client = RestClient.builder().requestFactory(factory).build();
  }

  public SendResult send(DispatchClaim claim) {
    try {
      var node = properties.trusted(claim.nodeKey(), claim.endpoint());
      if (node.serviceToken() == null || node.serviceToken().isBlank())
        return new SendResult.Failed(DispatchError.UNTRUSTED_NODE, false, null);
      JobAccepted accepted =
          client
              .post()
              .uri(claim.endpoint() + "/internal/v1/jobs")
              .headers(h -> h.setBearerAuth(node.serviceToken()))
              .header(CollectorHttpHeaders.IDEMPOTENCY_KEY, claim.request().attemptId().toString())
              .contentType(MediaType.APPLICATION_JSON)
              .body(claim.request())
              .exchange(
                  (request, response) -> {
                    if (response.getStatusCode().value() != HttpStatus.CREATED.value()) {
                      if (response.getStatusCode().isError())
                        throw new HttpClientErrorException(response.getStatusCode());
                      throw new IllegalArgumentException("Expected durable 201 acceptance");
                    }
                    JobAccepted body = response.bodyTo(JobAccepted.class);
                    if (body == null) throw new IllegalArgumentException("Missing durable acceptance");
                    return body;
                  });
      return new SendResult.Accepted(accepted);
    } catch (RestClientResponseException error) {
      int code = error.getStatusCode().value();
      boolean retry = code == 408 || code == 429 || code >= 500;
      return new SendResult.Failed(
          retry ? DispatchError.HTTP_RETRY : DispatchError.HTTP_REJECTED,
          retry,
          retry ? retryAfter(error) : null);
    } catch (RestClientException error) {
      return new SendResult.Failed(DispatchError.TRANSPORT, true, null);
    } catch (IllegalArgumentException error) {
      return new SendResult.Failed(DispatchError.INVALID_ACCEPTANCE, false, null);
    } catch (IllegalStateException error) {
      return new SendResult.Failed(DispatchError.UNTRUSTED_NODE, false, null);
    }
  }

  private static Duration retryAfter(RestClientResponseException error) {
    String header =
        error.getResponseHeaders() == null
            ? null
            : error.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER);
    if (header == null || header.isBlank()) return null;
    try {
      long seconds = Long.parseLong(header.trim());
      if (seconds < 0) return null;
      return Duration.ofSeconds(Math.min(seconds, 3600));
    } catch (NumberFormatException ignored) {
      try {
        Duration until =
            Duration.between(Instant.now(), ZonedDateTime.parse(header.trim()).toInstant());
        return until.isNegative() ? Duration.ZERO : until;
      } catch (RuntimeException parse) {
        return null;
      }
    }
  }
}
