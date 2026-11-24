package com.kholodilin.collector.integration.credentials;

import static org.assertj.core.api.Assertions.*;

import com.kholodilin.collector.contracts.CollectorProtocol.*;
import com.kholodilin.collector.model.CredentialSettings;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;

class HttpCredentialResolverTest {
  private static CredentialSettings settings(URI endpoint, String serviceToken) {
    return new CredentialSettings(
        Duration.ofHours(2),
        100,
        1000,
        Duration.ofSeconds(5),
        Duration.ofSeconds(3),
        Duration.ofSeconds(10),
        List.of(new CredentialSettings.Origin("rgm", endpoint, serviceToken)));
  }

  @Test
  void sendsOnlyToTrustedAuthorityAndNeverFollowsRedirectsOrLeaksResponseBodies() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    AtomicInteger leaks = new AtomicInteger();
    AtomicReference<String> auth = new AtomicReference<>();
    String serviceToken = "separate-service-token-0123456789012";
    var lease =
        new CredentialLease(
            1, "context", "ref", "1", "github-secret", null, Instant.now().plusSeconds(60));
    byte[] json = JsonMapper.builder().build().writeValueAsBytes(lease);
    server.createContext(
        "/internal/v1/credentials/resolve",
        exchange -> {
          auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, json.length);
          exchange.getResponseBody().write(json);
          exchange.close();
        });
    server.createContext(
        "/redirect/internal/v1/credentials/resolve",
        exchange -> {
          exchange.getResponseHeaders().set("Location", "/leak");
          exchange.sendResponseHeaders(307, -1);
          exchange.close();
        });
    server.createContext(
        "/leak",
        exchange -> {
          leaks.incrementAndGet();
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    server.start();
    try {
      URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
      var request = new CredentialRequest(1, UUID.randomUUID(), UUID.randomUUID());
      var properties = settings(URI.create(endpoint.toString()), serviceToken);
      assertThat(
              new HttpCredentialResolver(properties, HttpCredentialResolver.restClient(properties))
                  .resolve("rgm", request))
          .isEqualTo(lease);
      assertThat(auth).hasValue("Bearer " + serviceToken);
      var redirect = settings(URI.create(endpoint + "/redirect"), serviceToken);
      assertThatThrownBy(() ->
              new HttpCredentialResolver(redirect, HttpCredentialResolver.restClient(redirect))
                  .resolve("rgm", request))
          .isInstanceOf(CredentialUnavailableException.class)
          .hasNoCause()
          .hasMessageNotContaining(serviceToken);
      assertThat(leaks).hasValue(0);
      assertThatThrownBy(() ->
              new HttpCredentialResolver(properties, HttpCredentialResolver.restClient(properties))
                  .resolve("untrusted", request))
          .isInstanceOf(CredentialUnavailableException.class);
    } finally {
      server.stop(0);
    }
  }
}
