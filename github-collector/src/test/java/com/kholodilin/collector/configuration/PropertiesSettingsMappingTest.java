package com.kholodilin.collector.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kholodilin.collector.model.CredentialSettings;
import com.kholodilin.collector.model.DeliverySettings;
import com.kholodilin.collector.model.RetrySettings;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Bound properties are validated here and handed to inner layers as plain settings. */
class PropertiesSettingsMappingTest {
  private static final String TOKEN = "service-token-with-at-least-32-characters";

  @Test
  void acceptancePropertiesDefaultToTheResultsDestination() {
    var settings = new AcceptanceProperties(null).toSettings();

    assertThat(settings.destinationRefs()).containsExactly("rgm-results");
    assertThat(new AcceptanceProperties(List.of("a", "b")).toSettings().destinationRefs())
        .containsExactlyInAnyOrder("a", "b");
  }

  @Test
  void httpPropertiesMapClientsAndStillRejectWeakOrDuplicateTokens() {
    var settings =
        new CollectorHttpProperties(true, List.of(new CollectorHttpProperties.Client("rgm", TOKEN)))
            .toSettings();

    assertThat(settings.clients()).hasSize(1);
    assertThat(settings.clients().get(0).origin()).isEqualTo("rgm");
    assertThat(settings.clients().get(0).token()).isEqualTo(TOKEN);
    assertThat(settings.clients().get(0).toString()).doesNotContain(TOKEN);
    assertThatThrownBy(() ->
            new CollectorHttpProperties(
                true, List.of(new CollectorHttpProperties.Client("rgm", "short"))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new CollectorHttpProperties(true, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void credentialPropertiesMapDefaultsAndOriginsWithoutLeakingTheToken() {
    var properties =
        new CollectorCredentialProperties(
            true,
            null,
            0,
            0,
            null,
            null,
            null,
            true,
            List.of(
                new CollectorCredentialProperties.Origin(
                    "rgm", URI.create("http://localhost:8080/credentials"), TOKEN)));

    CredentialSettings settings = properties.toSettings();

    assertThat(settings.ttl()).isEqualTo(Duration.ofHours(2));
    assertThat(settings.maximumSize()).isEqualTo(100);
    assertThat(settings.maximumRejected()).isEqualTo(1000);
    assertThat(settings.failureBackoff()).isEqualTo(Duration.ofSeconds(5));
    assertThat(settings.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
    assertThat(settings.readTimeout()).isEqualTo(Duration.ofSeconds(10));
    assertThat(settings.origins()).hasSize(1);
    assertThat(settings.origins().get(0).serviceToken()).isEqualTo(TOKEN);
    assertThat(settings.origins().get(0).toString()).doesNotContain(TOKEN);
  }

  @Test
  void credentialPropertiesRejectPlainHttpUnlessAllowed() {
    var origin =
        new CollectorCredentialProperties.Origin(
            "rgm", URI.create("http://localhost:8080/credentials"), TOKEN);

    assertThatThrownBy(() ->
            new CollectorCredentialProperties(
                true, null, 0, 0, null, null, null, false, List.of(origin)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void deliveryPropertiesMapRetryPolicyAndDestinations() {
    var properties =
        new DeliveryProperties(
            true,
            null,
            null,
            null,
            0,
            true,
            List.of(
                new DeliveryProperties.Destination(
                    "rgm-results", "rgm", URI.create("http://localhost:8080/results"))),
            null);

    DeliverySettings settings = properties.toSettings();

    assertThat(settings.lease()).isEqualTo(Duration.ofSeconds(60));
    assertThat(settings.pollInterval()).isEqualTo(Duration.ofMillis(500));
    RetrySettings retry = settings.retry();
    assertThat(retry.maxAttempts()).isEqualTo(12);
    assertThat(retry.initialDelay()).isEqualTo(Duration.ofSeconds(5));
    assertThat(retry.maxDelay()).isEqualTo(Duration.ofMinutes(5));
    assertThat(retry.multiplier()).isEqualTo(2.0);
    assertThat(retry.jitter()).isEqualTo(0.2);
    assertThat(settings.destinations()).hasSize(1);
    assertThat(settings.destinations().get(0).ref()).isEqualTo("rgm-results");
  }

  @Test
  void deliveryPropertiesStillRejectDuplicateDestinationsAndMissingOnes() {
    var destination =
        new DeliveryProperties.Destination("ref", "rgm", URI.create("https://rgm.example/results"));

    assertThatThrownBy(() ->
            new DeliveryProperties(
                true, null, null, null, 0, false, List.of(destination, destination), null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new DeliveryProperties(true, null, null, null, 0, false, List.of(), null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void executionPropertiesMapDefaultsToSettings() {
    var settings =
        new ExecutionProperties(true, null, false, null, null, null, 0, 0, 0).toSettings();

    assertThat(settings.apiBaseUrl()).isEqualTo(URI.create("https://api.github.com"));
    assertThat(settings.lease()).isEqualTo(Duration.ofMinutes(1));
    assertThat(settings.pollInterval()).isEqualTo(Duration.ofMillis(250));
    assertThat(settings.requestTimeout()).isEqualTo(Duration.ofSeconds(10));
    assertThat(settings.maximumFailures()).isEqualTo(5);
    assertThat(settings.maximumPages()).isEqualTo(5);
    assertThat(settings.maximumPayloadBytes()).isEqualTo(5 * 1024 * 1024);
  }

  @Test
  void executionPropertiesRejectUntrustedApiBasesAndShortLeases() {
    assertThatThrownBy(() ->
            new ExecutionProperties(
                true, URI.create("http://api.example"), false, null, null, null, 0, 0, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() ->
            new ExecutionProperties(
                true, null, false, Duration.ofSeconds(10), null, Duration.ofSeconds(10), 0, 0, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(
            new ExecutionProperties(
                    true, URI.create("http://api.example"), true, null, null, null, 0, 0, 0)
                .apiBaseUrl())
        .isEqualTo(URI.create("http://api.example"));
  }

  @Test
  void gitHubClientPropertiesMapPacingAndRetryToSettings() {
    var defaults = new GitHubClientProperties(null, null);

    var pacing = defaults.pacingSettings();
    assertThat(pacing.concurrency()).isEqualTo(1);
    assertThat(pacing.interval("search")).isEqualTo(Duration.ofMillis(3000));
    assertThat(pacing.interval("graphql")).isEqualTo(Duration.ofMillis(1200));
    assertThat(pacing.interval("core")).isEqualTo(Duration.ofMillis(1200));
    var retry = defaults.retrySettings();
    assertThat(retry.maxAttempts()).isEqualTo(5);
    assertThat(retry.initialDelay()).isEqualTo(Duration.ofSeconds(5));
    assertThat(retry.maxDelay()).isEqualTo(Duration.ofMinutes(5));
    assertThat(retry.multiplier()).isEqualTo(2.0);
    assertThat(retry.jitter()).isEqualTo(0.2);
  }

  @Test
  void gitHubClientPropertiesStillRejectInvalidPolicies() {
    assertThatThrownBy(() ->
            new GitHubClientProperties.Pacing(
                33, Duration.ofMillis(1), Duration.ofMillis(1), Duration.ofMillis(1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new GitHubClientProperties.Retry(21, null, null, 0, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() ->
            new GitHubClientProperties.Retry(
                1, Duration.ofSeconds(10), Duration.ofSeconds(1), 2, 0.2))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void deliveryLeaseMustOutlastTheCallbackTimeouts() {
    var credentials =
        new CredentialSettings(
            Duration.ofHours(1),
            1,
            1,
            Duration.ofSeconds(1),
            Duration.ofSeconds(10),
            Duration.ofSeconds(20),
            List.of());
    RetrySettings retry = new RetrySettings(1, Duration.ofSeconds(1), Duration.ofSeconds(1), 1, 0);

    // 10s connect + 20s read + 5s margin = 35s; a 35s lease is not enough, 36s is.
    assertThatThrownBy(() ->
            CollectorDeliveryConfiguration.requireLeaseExceedsTimeouts(
                new DeliverySettings(
                    Duration.ofSeconds(35), Duration.ofMillis(1), retry, List.of()),
                credentials))
        .isInstanceOf(IllegalArgumentException.class);
    CollectorDeliveryConfiguration.requireLeaseExceedsTimeouts(
        new DeliverySettings(Duration.ofSeconds(36), Duration.ofMillis(1), retry, List.of()),
        credentials);
  }
}
