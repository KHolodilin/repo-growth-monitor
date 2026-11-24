package com.kholodilin.collector.integration.github;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.kholodilin.collector.contracts.CollectorProtocol.*;
import com.kholodilin.collector.integration.credentials.CredentialCache;
import com.kholodilin.collector.integration.credentials.CredentialedGitHubRequest;
import com.kholodilin.collector.model.CredentialSettings;
import com.kholodilin.collector.model.ExecutionSettings;
import com.kholodilin.collector.model.GitHubRateLimitedException;
import com.kholodilin.collector.model.GitHubSourceUnavailableException;
import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;

class GitHubSourceReaderTest {
  HttpServer server;
  URI endpoint;
  AtomicInteger permits = new AtomicInteger(),
      reports = new AtomicInteger(),
      resolved = new AtomicInteger(),
      calls = new AtomicInteger();
  List<String> resources = new ArrayList<>();
  HttpHandler source;
  GitHubSourceReader reader;
  JsonMapper json = JsonMapper.builder().build();
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
          "results",
          Instant.now().plusSeconds(600),
          new AuthorizationScope("ctx", "main"));

  @BeforeEach
  void setup() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    server.createContext(
        "/internal/v1/github-budget/acquire",
        exchange -> {
          resources.add(
              json.readTree(exchange.getRequestBody().readAllBytes()).path("resource").asText());
          permits.incrementAndGet();
          respond(
              exchange,
              200,
              json.writeValueAsString(
                  Map.of(
                      "permitId",
                      UUID.randomUUID(),
                      "leasedUntil",
                      Instant.now().plusSeconds(60))));
        });
    server.createContext(
        "/internal/v1/github-budget/report",
        exchange -> {
          reports.incrementAndGet();
          exchange.getRequestBody().readAllBytes();
          respond(exchange, 204, "");
        });
    server.createContext(
        "/",
        exchange -> {
          calls.incrementAndGet();
          source.handle(exchange);
        });
    server.start();
    var credentials =
        new CredentialSettings(
            Duration.ofHours(2),
            100,
            1000,
            Duration.ofSeconds(5),
            Duration.ofSeconds(1),
            Duration.ofSeconds(1),
            List.of(
                new CredentialSettings.Origin(
                    "rgm", endpoint, "node-service-secret-0123456789012345")));
    var cache =
        new CredentialCache(
            (origin, request) -> {
              int version = resolved.incrementAndGet();
              return new CredentialLease(
                  1,
                  "ctx",
                  "main",
                  Integer.toString(version),
                  "github-token-" + version,
                  null,
                  Instant.now().plusSeconds(600));
            },
            credentials,
            Clock.systemUTC(),
            com.github.benmanes.caffeine.cache.Ticker.systemTicker());
    var pacer = mock(LocalGitHubPacer.class);
    when(pacer.tryAcquire(anyString(), anyString()))
        .thenAnswer(invocation -> Optional.of((CredentialedGitHubRequest.Permit) () -> {}));
    var execution =
        new ExecutionSettings(
            endpoint,
            Duration.ofMinutes(1),
            Duration.ofMillis(250),
            Duration.ofSeconds(1),
            5,
            5,
            5 * 1024 * 1024);
    reader =
        new GitHubSourceReader(
            cache, credentials, execution, pacer, GitHubSourceReader.restClient(execution), json);
  }

  void respond(HttpExchange exchange, int status, String text) throws java.io.IOException {
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
    if (status != 204) exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void everyNetworkCallReportsItsPermitAndNeverCachesLocally() {
    source =
        exchange -> {
          assertThat(exchange.getRequestHeaders().getFirst("Authorization"))
              .isEqualTo("Bearer github-token-1");
          respond(exchange, 200, "{\"stargazers_count\":0}");
        };
    assertThat(
            reader
                .fetch("rgm", job, "repository", "GET", "/repos/owner/repo", null, false)
                .path("stargazers_count")
                .asInt())
        .isZero();
    reader.fetch("rgm", job, "repository", "GET", "/repos/owner/repo", null, false);
    assertThat(calls.get()).isEqualTo(2);
    assertThat(permits.get()).isEqualTo(2);
    assertThat(reports.get()).isEqualTo(2);
    assertThat(resources).containsExactly("core", "core");
  }

  @Test
  void rejectedTokenRefreshesExactlyOnceAndRetryGetsANewSharedPermit() {
    source = exchange -> respond(exchange, calls.get() == 1 ? 401 : 200, "{}");
    reader.fetch("rgm", job, "repository", "GET", "/repos/owner/repo", null, false);
    assertThat(resolved.get()).isEqualTo(2);
    assertThat(permits.get()).isEqualTo(2);
    assertThat(reports.get()).isEqualTo(2);
  }

  @Test
  void rateLimitIsDeferredWithoutRefreshingTheCredential() {
    source =
        exchange -> {
          exchange.getResponseHeaders().set("Retry-After", "60");
          respond(exchange, 429, "{}");
        };
    assertThatThrownBy(() ->
            reader.fetch("rgm", job, "search", "GET", "/search/repositories?q=test", null, false))
        .isInstanceOfSatisfying(
            GitHubRateLimitedException.class,
            e -> assertThat(e.retryAt()).isAfter(Instant.now().plusSeconds(30)));
    assertThat(resolved.get()).isEqualTo(1);
    assertThat(resources).containsExactly("search");
  }

  @Test
  void contributorLinkCountAndMissingOptionalFilePreserveMeaning() {
    source =
        exchange -> {
          exchange
              .getResponseHeaders()
              .set(
                  "Link",
                  "<https://api.github.com/repos/owner/repo/contributors?per_page=1&page=137>; rel=\"last\"");
          respond(exchange, 200, "[{\"login\":\"someone\"}]");
        };
    assertThat(
            reader
                .fetch(
                    "rgm",
                    job,
                    "contributor-count",
                    "GET",
                    "/repos/owner/repo/contributors?per_page=1",
                    null,
                    false)
                .path("total")
                .asInt())
        .isEqualTo(137);
    source = exchange -> respond(exchange, 404, "{}");
    assertThat(
            reader
                .fetch("rgm", job, "readme", "GET", "/repos/owner/repo/readme", null, true)
                .isNull())
        .isTrue();
    assertThat(resolved.get()).isEqualTo(1);
  }

  @Test
  void redirectsNeverCarryTheGithubSecretToAnotherEndpoint() {
    source =
        exchange -> {
          exchange.getResponseHeaders().set("Location", endpoint + "/leak");
          respond(exchange, 307, "");
        };
    assertThatThrownBy(
            () -> reader.fetch("rgm", job, "repository", "GET", "/repos/owner/repo", null, false))
        .isInstanceOf(GitHubSourceUnavailableException.class);
    assertThat(calls.get()).isEqualTo(1);
  }
}
