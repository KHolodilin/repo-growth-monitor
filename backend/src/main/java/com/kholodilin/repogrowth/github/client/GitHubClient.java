package com.kholodilin.repogrowth.github.client;

import com.kholodilin.repogrowth.common.api.ErrorCode;
import com.kholodilin.repogrowth.common.config.GitHubProperties;
import com.kholodilin.repogrowth.common.observability.AppMetrics;
import com.kholodilin.repogrowth.github.exception.GitHubException;
import com.kholodilin.repogrowth.github.model.GitHubCommitResponse;
import com.kholodilin.repogrowth.github.model.GitHubCommunityProfileResponse;
import com.kholodilin.repogrowth.github.model.GitHubPathResponse;
import com.kholodilin.repogrowth.github.model.GitHubReadmeResponse;
import com.kholodilin.repogrowth.github.model.GitHubReferrerResponse;
import com.kholodilin.repogrowth.github.model.GitHubReleaseResponse;
import com.kholodilin.repogrowth.github.model.GitHubRepositoryResponse;
import com.kholodilin.repogrowth.github.model.GitHubSearchItem;
import com.kholodilin.repogrowth.github.model.GitHubSearchResponse;
import com.kholodilin.repogrowth.github.model.GitHubTrafficClonesResponse;
import com.kholodilin.repogrowth.github.model.GitHubTrafficViewsResponse;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class GitHubClient {

    private static final Pattern NEXT_LINK = Pattern.compile("<([^>]+)>;\\s*rel=\"next\"");
    private static final Pattern LAST_LINK = Pattern.compile("<([^>]+)>;\\s*rel=\"last\"");
    private static final Pattern PAGE_QUERY = Pattern.compile("[?&]page=(\\d+)");
    private static final int MAX_PER_PAGE = 100;

    private final RestClient restClient;
    private final JsonMapper jsonMapper;
    private final GitHubProperties properties;
    private final AppMetrics metrics;

    public GitHubClient(
            RestClient gitHubRestClient,
            JsonMapper jsonMapper,
            GitHubProperties properties,
            AppMetrics metrics
    ) {
        this.restClient = gitHubRestClient;
        this.jsonMapper = jsonMapper;
        this.properties = properties;
        this.metrics = metrics;
    }

    public List<GitHubRepositoryResponse> listAccessibleRepositories() {
        requireToken();
        List<GitHubRepositoryResponse> all = new ArrayList<>();
        String path = "/user/repos?per_page=100&affiliation=owner,collaborator,organization_member&sort=full_name";
        while (path != null) {
            ResponseEntity<byte[]> response = execute("listRepositories", "GET", path);
            all.addAll(read(response.getBody(), new TypeReference<List<GitHubRepositoryResponse>>() {
            }));
            path = nextPath(response.getHeaders().getFirst(HttpHeaders.LINK));
        }
        return all;
    }

    public GitHubRepositoryResponse getRepository(String owner, String name) {
        requireToken();
        ResponseEntity<byte[]> response = execute("getRepository", "GET", "/repos/" + owner + "/" + name);
        return read(response.getBody(), GitHubRepositoryResponse.class);
    }

    public GitHubTrafficViewsResponse getViews(String owner, String name) {
        requireToken();
        ResponseEntity<byte[]> response = execute("views", "GET", "/repos/" + owner + "/" + name + "/traffic/views");
        return read(response.getBody(), GitHubTrafficViewsResponse.class);
    }

    public GitHubTrafficClonesResponse getClones(String owner, String name) {
        requireToken();
        ResponseEntity<byte[]> response = execute("clones", "GET", "/repos/" + owner + "/" + name + "/traffic/clones");
        return read(response.getBody(), GitHubTrafficClonesResponse.class);
    }

    public List<GitHubReferrerResponse> getReferrers(String owner, String name) {
        requireToken();
        ResponseEntity<byte[]> response = execute(
                "referrers",
                "GET",
                "/repos/" + owner + "/" + name + "/traffic/popular/referrers"
        );
        return read(response.getBody(), new TypeReference<List<GitHubReferrerResponse>>() {
        });
    }

    public List<GitHubPathResponse> getPopularPaths(String owner, String name) {
        requireToken();
        ResponseEntity<byte[]> response = execute(
                "popularPaths",
                "GET",
                "/repos/" + owner + "/" + name + "/traffic/popular/paths"
        );
        return read(response.getBody(), new TypeReference<List<GitHubPathResponse>>() {
        });
    }

    public int countContributors(String owner, String name) {
        requireToken();
        String path = "/repos/" + owner + "/" + name + "/contributors?per_page=1&anon=true";
        ResponseEntity<byte[]> response = execute("contributors", "GET", path);
        String last = lastPath(response.getHeaders().getFirst(HttpHeaders.LINK));
        if (last != null) {
            Matcher page = PAGE_QUERY.matcher(last);
            if (page.find()) {
                return Integer.parseInt(page.group(1));
            }
        }
        List<Object> items = read(response.getBody() == null || response.getBody().length == 0
                ? "[]".getBytes(StandardCharsets.UTF_8)
                : response.getBody(), new TypeReference<List<Object>>() {
        });
        return items.size();
    }

    public Optional<Instant> latestCommitAt(String owner, String name) {
        requireToken();
        try {
            ResponseEntity<byte[]> response = execute(
                    "latestCommit",
                    "GET",
                    "/repos/" + owner + "/" + name + "/commits?per_page=1"
            );
            byte[] body = response.getBody();
            if (body == null || body.length == 0) {
                return Optional.empty();
            }
            List<GitHubCommitResponse> commits = read(body, new TypeReference<List<GitHubCommitResponse>>() {
            });
            if (commits.isEmpty()) {
                return Optional.empty();
            }
            return Optional.ofNullable(commits.getFirst().committedAt());
        } catch (GitHubException ex) {
            if (ex.errorCode() == ErrorCode.GITHUB_AUTH_ERROR
                    || ex.errorCode() == ErrorCode.GITHUB_RATE_LIMIT_EXCEEDED
                    || ex.retryable()) {
                throw ex;
            }
            log.warn("Latest commit lookup failed owner={} name={} error={}", owner, name, ex.errorCode());
            return Optional.empty();
        }
    }

    public GitHubCommunityProfileResponse getCommunityProfile(String owner, String name) {
        requireToken();
        try {
            ResponseEntity<byte[]> response = execute(
                    "communityProfile",
                    "GET",
                    "/repos/" + owner + "/" + name + "/community/profile"
            );
            return read(response.getBody(), GitHubCommunityProfileResponse.class);
        } catch (GitHubException ex) {
            if (ex.retryable() || ex.errorCode() == ErrorCode.GITHUB_AUTH_ERROR
                    || ex.errorCode() == ErrorCode.GITHUB_RATE_LIMIT_EXCEEDED) {
                throw ex;
            }
            log.warn("Community profile lookup failed owner={} name={} error={}", owner, name, ex.errorCode());
            return GitHubCommunityProfileResponse.empty();
        }
    }

    public Optional<String> getReadme(String owner, String name) {
        requireToken();
        try {
            ResponseEntity<byte[]> response = execute("readme", "GET", "/repos/" + owner + "/" + name + "/readme");
            GitHubReadmeResponse readme = read(response.getBody(), GitHubReadmeResponse.class);
            String text = readme.decodedText();
            return text.isBlank() ? Optional.empty() : Optional.of(text);
        } catch (GitHubException ex) {
            if (ex.retryable() || ex.errorCode() == ErrorCode.GITHUB_AUTH_ERROR
                    || ex.errorCode() == ErrorCode.GITHUB_RATE_LIMIT_EXCEEDED) {
                throw ex;
            }
            return Optional.empty();
        }
    }

    public Optional<Instant> latestReleaseAt(String owner, String name) {
        requireToken();
        try {
            ResponseEntity<byte[]> response = execute(
                    "latestRelease",
                    "GET",
                    "/repos/" + owner + "/" + name + "/releases?per_page=1"
            );
            byte[] body = response.getBody();
            if (body == null || body.length == 0) {
                return Optional.empty();
            }
            List<GitHubReleaseResponse> releases = read(body, new TypeReference<List<GitHubReleaseResponse>>() {
            });
            return releases.stream()
                    .filter(release -> !release.draft())
                    .map(GitHubReleaseResponse::timestamp)
                    .filter(timestamp -> timestamp != null)
                    .findFirst();
        } catch (GitHubException ex) {
            if (ex.retryable() || ex.errorCode() == ErrorCode.GITHUB_AUTH_ERROR
                    || ex.errorCode() == ErrorCode.GITHUB_RATE_LIMIT_EXCEEDED) {
                throw ex;
            }
            log.warn("Latest release lookup failed owner={} name={} error={}", owner, name, ex.errorCode());
            return Optional.empty();
        }
    }

    public boolean fileExists(String owner, String name, String path) {
        requireToken();
        try {
            execute("contents", "GET", "/repos/" + owner + "/" + name + "/contents/" + path);
            return true;
        } catch (GitHubException ex) {
            if (ex.retryable() || ex.errorCode() == ErrorCode.GITHUB_AUTH_ERROR
                    || ex.errorCode() == ErrorCode.GITHUB_RATE_LIMIT_EXCEEDED) {
                throw ex;
            }
            return false;
        }
    }

    public GitHubSearchResponse searchRepositories(String query, int limit) {
        requireToken();
        int remaining = Math.max(1, limit);
        int page = 1;
        int totalCount = 0;
        List<GitHubSearchItem> items = new ArrayList<>();
        while (remaining > 0) {
            int perPage = Math.min(MAX_PER_PAGE, remaining);
            String path = "/search/repositories?q={query}&per_page={perPage}&page={page}";
            ResponseEntity<byte[]> response = executeSearch(query, perPage, page, path);
            GitHubSearchResponse parsed = read(response.getBody(), GitHubSearchResponse.class);
            totalCount = parsed.totalCount();
            List<GitHubSearchItem> pageItems = parsed.itemsOrEmpty();
            items.addAll(pageItems);
            if (pageItems.size() < perPage) {
                break;
            }
            remaining -= pageItems.size();
            page++;
        }
        return new GitHubSearchResponse(totalCount, items);
    }

    public String maskedToken() {
        String token = properties.token();
        if (token == null || token.isBlank()) {
            return null;
        }
        if (token.length() <= 8) {
            return "****";
        }
        return token.substring(0, Math.min(11, token.length() - 4)) + "****" + token.substring(token.length() - 4);
    }

    private ResponseEntity<byte[]> executeSearch(String query, int perPage, int page, String uriTemplate) {
        requireToken();
        Timer.Sample sample = metrics.startTimer();
        long started = System.nanoTime();
        try {
            ResponseEntity<byte[]> response = restClient.get()
                    .uri(uriTemplate, query, perPage, page)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, this::handleError)
                    .toEntity(byte[].class);
            metrics.githubRequest("search", true);
            log.info("GitHub request durationMs={} operation=search", durationMs(started));
            return response;
        } catch (GitHubException ex) {
            metrics.githubRequest("search", false);
            log.info("GitHub request durationMs={} operation=search errorCategory={}", durationMs(started), ex.errorCode());
            throw ex;
        } catch (ResourceAccessException ex) {
            metrics.githubRequest("search", false);
            throw translateAccess(ex);
        } finally {
            sample.stop(io.micrometer.core.instrument.Timer.builder("github.api.duration")
                    .tag("operation", "search")
                    .register(io.micrometer.core.instrument.Metrics.globalRegistry));
        }
    }

    private ResponseEntity<byte[]> execute(String operation, String method, String path) {
        Timer.Sample sample = metrics.startTimer();
        long started = System.nanoTime();
        try {
            ResponseEntity<byte[]> response = restClient.get()
                    .uri(path)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, this::handleError)
                    .toEntity(byte[].class);
            metrics.githubRequest(operation, true);
            log.info("GitHub request durationMs={} operation={}", durationMs(started), operation);
            return response;
        } catch (GitHubException ex) {
            metrics.githubRequest(operation, false);
            log.info("GitHub request durationMs={} operation={} errorCategory={}",
                    durationMs(started), operation, ex.errorCode());
            throw ex;
        } catch (ResourceAccessException ex) {
            metrics.githubRequest(operation, false);
            throw translateAccess(ex);
        } finally {
            sample.stop(io.micrometer.core.instrument.Timer.builder("github.api.duration")
                    .tag("operation", operation)
                    .register(io.micrometer.core.instrument.Metrics.globalRegistry));
        }
    }

    private void handleError(HttpRequest request, ClientHttpResponse response) throws IOException {
        int status = response.getStatusCode().value();
        Instant reset = rateLimitReset(response.getHeaders());
        boolean remainingZero = remainingZero(response.getHeaders());
        String body = bodyAsString(response);
        String message = Optional.ofNullable(body).filter(s -> !s.isBlank()).orElse("GitHub API error " + status);

        if (status == 401) {
            throw GitHubException.auth(status, "GitHub authentication failed");
        }
        if (status == 403 && remainingZero) {
            throw GitHubException.rateLimit(reset, "GitHub API rate limit exceeded");
        }
        if (status == 403 && message.toLowerCase().contains("rate limit")) {
            throw GitHubException.rateLimit(reset, "GitHub API rate limit exceeded");
        }
        if (status == 404) {
            throw GitHubException.notFound("GitHub resource not found");
        }
        if (status == 422) {
            throw GitHubException.validation("Invalid GitHub search query");
        }
        boolean retryable = status >= 500 || status == 429;
        if (status == 429) {
            throw GitHubException.rateLimit(reset, "GitHub API rate limit exceeded");
        }
        throw GitHubException.api(status, retryable, reset, message);
    }

    private GitHubException translateAccess(ResourceAccessException ex) {
        Throwable cause = ex.getCause();
        if (cause instanceof SocketTimeoutException) {
            return GitHubException.timeout(ex);
        }
        return GitHubException.network(ex);
    }

    private void requireToken() {
        if (!properties.tokenConfigured()) {
            throw GitHubException.auth(401, "GitHub token is not configured");
        }
    }

    private <T> T read(byte[] body, Class<T> type) {
        if (body == null || body.length == 0) {
            throw GitHubException.malformed(new IllegalStateException("Empty GitHub payload"));
        }
        try {
            return jsonMapper.readValue(body, type);
        } catch (JacksonException ex) {
            throw GitHubException.malformed(ex);
        }
    }

    private <T> T read(byte[] body, TypeReference<T> type) {
        if (body == null || body.length == 0) {
            throw GitHubException.malformed(new IllegalStateException("Empty GitHub payload"));
        }
        try {
            return jsonMapper.readValue(body, type);
        } catch (JacksonException ex) {
            throw GitHubException.malformed(ex);
        }
    }

    private String lastPath(String linkHeader) {
        if (linkHeader == null || linkHeader.isBlank()) {
            return null;
        }
        Matcher matcher = LAST_LINK.matcher(linkHeader);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1);
    }

    private String nextPath(String linkHeader) {
        if (linkHeader == null || linkHeader.isBlank()) {
            return null;
        }
        Matcher matcher = NEXT_LINK.matcher(linkHeader);
        if (!matcher.find()) {
            return null;
        }
        String next = matcher.group(1);
        String base = properties.apiBaseUrl();
        if (next.startsWith(base)) {
            return next.substring(base.length());
        }
        return next;
    }

    private Instant rateLimitReset(HttpHeaders headers) {
        String reset = headers.getFirst("X-RateLimit-Reset");
        if (reset == null || reset.isBlank()) {
            String retryAfter = headers.getFirst(HttpHeaders.RETRY_AFTER);
            if (retryAfter != null) {
                try {
                    return Instant.now().plusSeconds(Long.parseLong(retryAfter));
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
            return null;
        }
        try {
            return Instant.ofEpochSecond(Long.parseLong(reset));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private boolean remainingZero(HttpHeaders headers) {
        String remaining = headers.getFirst("X-RateLimit-Remaining");
        return "0".equals(remaining);
    }

    private String bodyAsString(ClientHttpResponse response) {
        try {
            byte[] bytes = response.getBody().readAllBytes();
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return "";
        }
    }

    private long durationMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
