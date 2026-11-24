package com.kholodilin.collector.integration.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import com.kholodilin.collector.model.CredentialSettings;
import com.kholodilin.collector.model.DeliverySettings;
import com.kholodilin.collector.model.RetrySettings;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ResultDeliveryClientTest {
  private static final String TOKEN = "service-token-with-at-least-32-characters";

  private HttpServer server;
  private final AtomicInteger status = new AtomicInteger(204);
  private final AtomicReference<String> authorization = new AtomicReference<>();
  private final AtomicReference<String> body = new AtomicReference<>();
  private final AtomicInteger hits = new AtomicInteger();
  private ResultDeliveryClient client;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/results",
        exchange -> {
          hits.incrementAndGet();
          authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
          body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          if (status.get() == 307) {
            exchange.getResponseHeaders().add("Location", "/elsewhere");
          }
          exchange.sendResponseHeaders(status.get(), -1);
          exchange.close();
        });
    server.start();
    client =
        clientFor(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/results"));
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private static ResultDeliveryClient clientFor(URI endpoint) {
    var credentials =
        new CredentialSettings(
            Duration.ofHours(1),
            10,
            10,
            Duration.ofSeconds(1),
            Duration.ofSeconds(1),
            Duration.ofSeconds(2),
            List.of(
                new CredentialSettings.Origin(
                    "rgm", URI.create("http://127.0.0.1:1/credentials"), TOKEN)));
    var delivery =
        new DeliverySettings(
            Duration.ofSeconds(60),
            Duration.ofMillis(10),
            new RetrySettings(3, Duration.ofSeconds(1), Duration.ofSeconds(5), 2.0, 0.0),
            List.of(new DeliverySettings.Destination("rgm-results", "rgm", endpoint)));
    return new ResultDeliveryClient(
        delivery, credentials, ResultDeliveryClient.restClient(credentials));
  }

  @Test
  void deliversPayloadWithTheOriginServiceTokenOnly() {
    var outcome = client.deliver("rgm", "rgm-results", "{\"a\":1}");

    assertThat(outcome).isEqualTo(DeliveryOutcome.DELIVERED);
    assertThat(authorization.get()).isEqualTo("Bearer " + TOKEN);
    assertThat(body.get()).isEqualTo("{\"a\":1}");
  }

  @ParameterizedTest
  @CsvSource({
    "408,RETRYABLE",
    "429,RETRYABLE",
    "500,RETRYABLE",
    "503,RETRYABLE",
    "400,REJECTED",
    "401,REJECTED",
    "404,REJECTED",
    "409,REJECTED"
  })
  void mapsHttpStatusToOutcome(int httpStatus, DeliveryOutcome expected) {
    status.set(httpStatus);

    assertThat(client.deliver("rgm", "rgm-results", "{}")).isEqualTo(expected);
  }

  @Test
  void neverFollowsRedirects() {
    status.set(307);

    assertThat(client.deliver("rgm", "rgm-results", "{}")).isEqualTo(DeliveryOutcome.REJECTED);
    assertThat(hits).hasValue(1);
  }

  @Test
  void unknownDestinationRefIsUnavailableWithoutAnyRequest() {
    assertThat(client.deliver("rgm", "missing", "{}"))
        .isEqualTo(DeliveryOutcome.DESTINATION_UNAVAILABLE);
    assertThat(hits).hasValue(0);
  }

  @Test
  void destinationOfAnotherOriginIsUnavailable() {
    assertThat(client.deliver("other", "rgm-results", "{}"))
        .isEqualTo(DeliveryOutcome.DESTINATION_UNAVAILABLE);
    assertThat(hits).hasValue(0);
  }

  @Test
  void transportFailureIsRetryable() {
    int closedPort = server.getAddress().getPort();
    server.stop(0);
    var offline = clientFor(URI.create("http://127.0.0.1:" + closedPort + "/results"));

    assertThat(offline.deliver("rgm", "rgm-results", "{}")).isEqualTo(DeliveryOutcome.RETRYABLE);
  }

  @ParameterizedTest
  @CsvSource({
    "204,DELIVERED",
    "408,RETRYABLE",
    "429,RETRYABLE",
    "500,RETRYABLE",
    "599,RETRYABLE",
    "200,REJECTED",
    "301,REJECTED",
    "400,REJECTED",
    "499,REJECTED"
  })
  void outcomeTable(int httpStatus, DeliveryOutcome expected) {
    assertThat(ResultDeliveryClient.outcome(httpStatus, "rgm-results")).isEqualTo(expected);
  }
}
