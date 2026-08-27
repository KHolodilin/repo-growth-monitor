package com.kholodilin.repogrowth.github.client;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.kholodilin.repogrowth.common.config.GitHubProperties;
import com.kholodilin.repogrowth.common.observability.AppMetrics;
import com.kholodilin.repogrowth.github.exception.GitHubException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitHubClientWireMockTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    private GitHubClient client;

    @BeforeEach
    void setUp() {
        GitHubProperties properties = new GitHubProperties(
                "test-token",
                wireMock.baseUrl(),
                Duration.ofSeconds(2),
                Duration.ofSeconds(2)
        );
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(2));
        RestClient restClient = RestClient.builder()
                .baseUrl(wireMock.baseUrl())
                .requestFactory(factory)
                .defaultHeader("Authorization", "Bearer test-token")
                .build();
        client = new GitHubClient(restClient, JsonMapper.builder().build(), properties, new AppMetrics(new SimpleMeterRegistry()));
    }

    @Test
    void listsRepositoriesWithPagination() {
        wireMock.stubFor(get(urlPathEqualTo("/user/repos"))
                .withQueryParam("page", equalTo("1"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withHeader("Link", "<" + wireMock.baseUrl() + "/user/repos?page=2>; rel=\"next\"")
                        .withBody("[{\"id\":1,\"name\":\"a\",\"full_name\":\"acme/a\",\"private\":false,\"fork\":false,\"archived\":false,\"stargazers_count\":1,\"forks_count\":0,\"open_issues_count\":0,\"owner\":{\"id\":10,\"login\":\"acme\",\"type\":\"User\"}}]")));
        wireMock.stubFor(get(urlPathEqualTo("/user/repos"))
                .withQueryParam("page", equalTo("2"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("[{\"id\":2,\"name\":\"b\",\"full_name\":\"acme/b\",\"private\":true,\"fork\":false,\"archived\":false,\"stargazers_count\":2,\"forks_count\":0,\"open_issues_count\":0,\"owner\":{\"id\":10,\"login\":\"acme\",\"type\":\"Organization\"}}]")));

        // first request has no page param
        wireMock.stubFor(get("/user/repos?per_page=100&affiliation=owner,collaborator,organization_member&sort=full_name")
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withHeader("Link", "<" + wireMock.baseUrl() + "/user/repos?page=2>; rel=\"next\"")
                        .withBody("[{\"id\":1,\"name\":\"a\",\"full_name\":\"acme/a\",\"private\":false,\"fork\":false,\"archived\":false,\"stargazers_count\":1,\"forks_count\":0,\"open_issues_count\":0,\"owner\":{\"id\":10,\"login\":\"acme\",\"type\":\"User\"}}]")));
        wireMock.stubFor(get("/user/repos?page=2")
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("[{\"id\":2,\"name\":\"b\",\"full_name\":\"acme/b\",\"private\":true,\"fork\":false,\"archived\":false,\"stargazers_count\":2,\"forks_count\":0,\"open_issues_count\":0,\"owner\":{\"id\":11,\"login\":\"acme\",\"type\":\"Organization\"}}]")));

        var repos = client.listAccessibleRepositories();
        assertThat(repos).hasSize(2);
        assertThat(repos.get(0).fullName()).isEqualTo("acme/a");
        assertThat(repos.get(1).resolvedVisibility()).isEqualTo("PRIVATE");
    }

    @Test
    void emptyReferrers() {
        wireMock.stubFor(get("/repos/acme/a/traffic/popular/referrers")
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("[]")));
        assertThat(client.getReferrers("acme", "a")).isEmpty();
    }

    @Test
    void unauthorized() {
        wireMock.stubFor(get("/repos/acme/a")
                .willReturn(aResponse().withStatus(401).withBody("{\"message\":\"Bad credentials\"}")));
        assertThatThrownBy(() -> client.getRepository("acme", "a"))
                .isInstanceOf(GitHubException.class)
                .extracting(ex -> ((GitHubException) ex).retryable())
                .isEqualTo(false);
    }

    @Test
    void forbiddenWithoutRateLimitIsNotRetryable() {
        wireMock.stubFor(get("/repos/acme/a/traffic/views")
                .willReturn(aResponse().withStatus(403).withHeader("X-RateLimit-Remaining", "10").withBody("{\"message\":\"forbidden\"}")));
        assertThatThrownBy(() -> client.getViews("acme", "a"))
                .isInstanceOf(GitHubException.class)
                .extracting(ex -> ((GitHubException) ex).retryable())
                .isEqualTo(false);
    }

    @Test
    void rateLimit() {
        wireMock.stubFor(get("/repos/acme/a/traffic/views")
                .willReturn(aResponse()
                        .withStatus(403)
                        .withHeader("X-RateLimit-Remaining", "0")
                        .withHeader("X-RateLimit-Reset", "2000000000")
                        .withBody("{\"message\":\"API rate limit exceeded\"}")));
        assertThatThrownBy(() -> client.getViews("acme", "a"))
                .isInstanceOf(GitHubException.class)
                .satisfies(ex -> {
                    GitHubException github = (GitHubException) ex;
                    assertThat(github.retryable()).isTrue();
                    assertThat(github.rateLimitReset()).isNotNull();
                });
    }

    @Test
    void notFound() {
        wireMock.stubFor(get("/repos/acme/missing").willReturn(aResponse().withStatus(404).withBody("{}")));
        assertThatThrownBy(() -> client.getRepository("acme", "missing")).isInstanceOf(GitHubException.class);
    }

    @Test
    void unprocessableSearchQuery() {
        wireMock.stubFor(get("/search/repositories")
                .willReturn(aResponse().withStatus(422).withBody("{\"message\":\"Validation Failed\"}")));
        assertThatThrownBy(() -> client.searchRepositories("bad:", 10)).isInstanceOf(GitHubException.class);
    }

    @Test
    void serverErrorIsRetryable() {
        wireMock.stubFor(get("/repos/acme/a").willReturn(aResponse().withStatus(500).withBody("oops")));
        assertThatThrownBy(() -> client.getRepository("acme", "a"))
                .isInstanceOf(GitHubException.class)
                .extracting(ex -> ((GitHubException) ex).retryable())
                .isEqualTo(true);
    }

    @Test
    void malformedPayload() {
        wireMock.stubFor(get("/repos/acme/a/traffic/views")
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("{not-json")));
        assertThatThrownBy(() -> client.getViews("acme", "a")).isInstanceOf(GitHubException.class);
    }

    @Test
    void timeout() {
        wireMock.stubFor(get("/repos/acme/a")
                .willReturn(aResponse().withFixedDelay(5000).withStatus(200).withBody("{}")));
        assertThatThrownBy(() -> client.getRepository("acme", "a")).isInstanceOf(GitHubException.class);
    }

    @Test
    void trafficViews() {
        wireMock.stubFor(get("/repos/acme/a/traffic/views")
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
                        {"count":8,"uniques":3,"views":[{"timestamp":"2026-08-20T00:00:00Z","count":8,"uniques":3}]}
                        """)));
        var views = client.getViews("acme", "a");
        assertThat(views.days()).hasSize(1);
        assertThat(views.days().get(0).date().toString()).isEqualTo("2026-08-20");
    }
}
