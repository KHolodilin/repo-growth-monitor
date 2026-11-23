package com.kholodilin.repogrowth.orchestration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

@WireMockTest
class CollectorNodeClientTest {
  private CollectorNodeClient client;
  private String endpoint;

  @BeforeEach
  void setUp(WireMockRuntimeInfo info) {
    endpoint = info.getHttpBaseUrl();
    client =
        new CollectorNodeClient(
            properties(
                List.of(
                    new OrchestrationProperties.Node(
                        "node-a",
                        "default",
                        URI.create(endpoint),
                        true,
                        "service-token-01234567890123456789"))),
            JsonMapper.builder().build());
  }

  private static OrchestrationProperties properties(List<OrchestrationProperties.Node> nodes) {
    return new OrchestrationProperties(
        true,
        "default",
        "rgm-results",
        Duration.ofHours(1),
        Duration.ofSeconds(30),
        Duration.ofSeconds(5),
        Duration.ofSeconds(2),
        Duration.ofSeconds(5),
        true,
        nodes,
        List.of());
  }

  @Test
  void statusAndResultTolerateNotFoundAndConfirmAcceptsNotFound() {
    stubFor(
        get(urlPathEqualTo("/internal/v1/jobs/by-attempt/11111111-1111-1111-1111-111111111111"))
            .willReturn(notFound()));
    stubFor(
        get(urlPathEqualTo("/internal/v1/jobs/22222222-2222-2222-2222-222222222222/result"))
            .willReturn(notFound()));
    stubFor(
        post(urlPathEqualTo("/internal/v1/jobs/22222222-2222-2222-2222-222222222222/delivery/ack"))
            .willReturn(notFound()));
    assertThat(
            client.status(
                "node-a", endpoint, null, UUID.fromString("11111111-1111-1111-1111-111111111111")))
        .isEmpty();
    UUID job = UUID.fromString("22222222-2222-2222-2222-222222222222");
    assertThat(client.result("node-a", endpoint, job)).isEmpty();
    client.confirm("node-a", endpoint, job, UUID.randomUUID());
  }

  @Test
  void missingServiceTokenFailsClosed() {
    var insecure =
        new CollectorNodeClient(
            properties(
                List.of(
                    new OrchestrationProperties.Node(
                        "node-a", "default", URI.create(endpoint), true, " "))),
            JsonMapper.builder().build());
    assertThatThrownBy(() -> insecure.ready("node-a", endpoint))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void nonSuccessStatusIsUnavailable() {
    stubFor(get(urlPathEqualTo("/internal/v1/status")).willReturn(aResponse().withStatus(503)));
    assertThatThrownBy(() -> client.ready("node-a", endpoint))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("unavailable");
  }
}
