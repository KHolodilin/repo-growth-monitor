package com.kholodilin.collector.integration.github;

import com.kholodilin.collector.contracts.CollectorProtocol.JobRequest;
import com.kholodilin.collector.model.ExecutionSettings;
import com.kholodilin.collector.model.IncompleteObservationException;
import com.kholodilin.collector.model.github.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.util.UriUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Typed GitHub observation operations for assigned collection. Owns URL/GraphQL/JSON; no
 * checkpoint.
 */
@RequiredArgsConstructor
public final class AssignedGithubClient {
  private final GitHubSourceReader source;
  private final ExecutionSettings settings;
  private final JsonMapper json;

  public int maximumPages() {
    return settings.maximumPages();
  }

  public RepositoryObservation repository(String origin, JobRequest job) {
    JsonNode remote = get(origin, job, "repository", repo(job), false);
    Long watchers =
        remote.hasNonNull("subscribers_count") ? number(remote, "subscribers_count") : null;
    return new RepositoryObservation(
        number(remote, "stargazers_count"),
        number(remote, "forks_count"),
        watchers,
        json.writeValueAsString(remote));
  }

  public ContributorPage contributorsPage(String origin, JobRequest job, int page) {
    JsonNode data =
        get(
            origin,
            job,
            "contributors:" + page,
            pagePath(repo(job) + "/contributors?anon=true", page),
            false);
    if (!data.isArray()) throw new IncompleteObservationException();
    List<ContributorPage.ContributorEntry> items = new ArrayList<>();
    for (JsonNode row : data) {
      String login =
          row.hasNonNull("login") && !row.get("login").asText().isBlank()
              ? row.get("login").asText().trim()
              : null;
      items.add(new ContributorPage.ContributorEntry(login));
    }
    // Full page ⇒ another page may exist (GitHub page size stays inside integration).
    return new ContributorPage(items, data.size() == 100, json.writeValueAsString(data));
  }

  public ContributorTotal contributorCount(String origin, JobRequest job) {
    JsonNode count =
        get(
            origin,
            job,
            "contributor-count",
            repo(job) + "/contributors?per_page=1&anon=true",
            false);
    return new ContributorTotal(number(count, "total"));
  }

  public OpaqueDocument readme(String origin, JobRequest job) {
    return optionalDocument(origin, job, "readme", repo(job) + "/readme");
  }

  public SocialPreview socialPreview(String origin, JobRequest job) {
    String body =
        json.writeValueAsString(
            Map.of(
                "query",
                "query($owner:String!,$name:String!){repository(owner:$owner,name:$name){usesCustomOpenGraphImage openGraphImageUrl}}",
                "variables",
                Map.of("owner", job.repository().owner(), "name", job.repository().name())));
    JsonNode preview = source.fetch(origin, job, "social-preview", "POST", "/graphql", body, true);
    if (preview == null || preview.isNull() || preview.hasNonNull("errors"))
      return SocialPreview.unused();
    JsonNode repository = preview.path("data").path("repository");
    if (repository.isMissingNode() || repository.isNull()) return SocialPreview.unused();
    return new SocialPreview(true, json.writeValueAsString(repository));
  }

  public OpaqueDocument community(String origin, JobRequest job) {
    return optionalDocument(origin, job, "community", repo(job) + "/community/profile");
  }

  public OpaqueDocument latestCommit(String origin, JobRequest job) {
    return optionalDocument(origin, job, "latest-commit", repo(job) + "/commits?per_page=1");
  }

  public OpaqueDocument latestRelease(String origin, JobRequest job) {
    return optionalDocument(origin, job, "latest-release", repo(job) + "/releases/latest");
  }

  public boolean contentExists(String origin, JobRequest job, String key, String relativePath) {
    JsonNode node = get(origin, job, key, repo(job) + "/contents/" + relativePath, true);
    return node != null && !node.isNull();
  }

  public JsonArrayPage issuesPage(String origin, JobRequest job, int page) {
    return arrayPage(
        origin,
        job,
        "issues:" + page,
        pagePath(repo(job) + "/issues?state=all&sort=created&direction=desc", page));
  }

  public JsonArrayPage pullsPage(String origin, JobRequest job, int page) {
    return arrayPage(
        origin,
        job,
        "pulls:" + page,
        pagePath(repo(job) + "/pulls?state=all&sort=created&direction=desc", page));
  }

  public JsonArrayPage releasesPage(String origin, JobRequest job, int page) {
    return arrayPage(origin, job, "releases:" + page, pagePath(repo(job) + "/releases", page));
  }

  public OpaqueDocument readmeCommits(String origin, JobRequest job, String readmeName) {
    return optionalDocument(
        origin,
        job,
        "readme-commit",
        repo(job)
            + "/commits?per_page=1&path="
            + URLEncoder.encode(readmeName, StandardCharsets.UTF_8));
  }

  public JsonArrayPage accessibleRepositoriesPage(String origin, JobRequest job, int page) {
    return arrayPage(
        origin,
        job,
        "accessible-repositories:" + page,
        pagePath(
            "/user/repos?affiliation=owner,collaborator,organization_member&sort=full_name", page));
  }

  public String ownedRepositoriesMetadata(JsonArrayPage page, String ownerLogin) {
    JsonNode array = json.readTree(page.rawJson());
    List<JsonNode> owned = new ArrayList<>();
    for (JsonNode item : array)
      if (item.path("owner").path("login").asText().equalsIgnoreCase(ownerLogin)) owned.add(item);
    return json.writeValueAsString(owned);
  }

  /** Concatenate opaque JSON array pages into one array string for Snapshot metadata. */
  public String mergeJsonArrays(List<String> pages) {
    if (pages == null || pages.isEmpty()) return "[]";
    var merged = json.createArrayNode();
    for (String page : pages) {
      JsonNode node = json.readTree(page);
      if (!node.isArray()) throw new IncompleteObservationException();
      node.forEach(merged::add);
    }
    return json.writeValueAsString(merged);
  }

  public TrafficSeries views(String origin, JobRequest job) {
    return trafficSeries(get(origin, job, "views", repo(job) + "/traffic/views", false), "views");
  }

  public TrafficSeries clones(String origin, JobRequest job) {
    return trafficSeries(
        get(origin, job, "clones", repo(job) + "/traffic/clones", false), "clones");
  }

  public PopularSources referrers(String origin, JobRequest job) {
    return popular(
        get(origin, job, "sources", repo(job) + "/traffic/popular/referrers", false), "referrer");
  }

  public PopularSources popularPaths(String origin, JobRequest job) {
    return popular(
        get(origin, job, "sources", repo(job) + "/traffic/popular/paths", false), "path");
  }

  public SearchObservation search(String origin, JobRequest job, String checkpointKey) {
    return parseSearch(get(origin, job, checkpointKey, searchPath(job), false), job);
  }

  private SearchObservation parseSearch(JsonNode data, JobRequest job) {
    long total = number(data, "total_count");
    boolean incomplete = data.path("incomplete_results").asBoolean(false);
    List<SearchObservation.Hit> items = new ArrayList<>();
    boolean allPublic = true;
    Integer position = null;
    int rank = 1;
    for (JsonNode row : data.path("items")) {
      String id = row.path("id").asText();
      if (id.equals(job.repository().githubId())) position = rank;
      boolean isPrivate =
          row.hasNonNull("private")
              && row.get("private").isBoolean()
              && row.get("private").asBoolean();
      if (!row.hasNonNull("private") || !row.get("private").isBoolean() || isPrivate)
        allPublic = false;
      items.add(
          new SearchObservation.Hit(
              id,
              row.path("full_name").asText(),
              number(row, "stargazers_count"),
              number(row, "forks_count"),
              isPrivate));
      rank++;
    }
    return new SearchObservation(
        total, incomplete, items, position, allPublic, json.writeValueAsString(data));
  }

  private TrafficSeries trafficSeries(JsonNode root, String arrayField) {
    List<TrafficSeries.Bucket> buckets = new ArrayList<>();
    for (JsonNode row : root.path(arrayField)) {
      LocalDate date =
          Instant.parse(row.get("timestamp").asText()).atZone(ZoneOffset.UTC).toLocalDate();
      buckets.add(new TrafficSeries.Bucket(date, number(row, "count"), number(row, "uniques")));
    }
    return new TrafficSeries(buckets);
  }

  private PopularSources popular(JsonNode data, String keyField) {
    if (!data.isArray()) throw new IncompleteObservationException();
    List<PopularSources.Entry> entries = new ArrayList<>();
    for (JsonNode row : data)
      entries.add(
          new PopularSources.Entry(
              row.get(keyField).asText(),
              row.hasNonNull("title") ? row.get("title").asText() : null,
              number(row, "count"),
              number(row, "uniques")));
    return new PopularSources(entries);
  }

  private JsonArrayPage arrayPage(String origin, JobRequest job, String key, String path) {
    JsonNode data = get(origin, job, key, path, false);
    if (!data.isArray()) throw new IncompleteObservationException();
    // Full page ⇒ another page may exist (GitHub page size stays inside integration).
    return new JsonArrayPage(json.writeValueAsString(data), data.size(), data.size() == 100);
  }

  private OpaqueDocument optionalDocument(String origin, JobRequest job, String key, String path) {
    JsonNode node = get(origin, job, key, path, true);
    if (node == null || node.isNull()) return OpaqueDocument.absent();
    String name = node.hasNonNull("name") ? node.get("name").asText() : null;
    return OpaqueDocument.of(json.writeValueAsString(node), name);
  }

  private JsonNode get(String origin, JobRequest job, String key, String path, boolean optional) {
    return source.fetch(origin, job, key, "GET", path, null, optional);
  }

  private String searchPath(JobRequest job) {
    int limit = job.parameters().resultLimit() == null ? 50 : job.parameters().resultLimit();
    String query = job.parameters().query();
    if (job.parameters().publicOnly()) query = query + " is:public";
    String path =
        "/search/repositories?q="
            + URLEncoder.encode(query, StandardCharsets.UTF_8)
            + "&per_page="
            + limit;
    String sort = job.parameters().sort();
    if (sort != null && !sort.isBlank())
      path +=
          "&sort="
              + URLEncoder.encode(sort, StandardCharsets.UTF_8)
              + "&order="
              + URLEncoder.encode(
                  Objects.requireNonNullElse(job.parameters().order(), "desc"),
                  StandardCharsets.UTF_8);
    return path;
  }

  private String repo(JobRequest job) {
    return "/repos/"
        + UriUtils.encodePathSegment(job.repository().owner(), StandardCharsets.UTF_8)
        + "/"
        + UriUtils.encodePathSegment(job.repository().name(), StandardCharsets.UTF_8);
  }

  private String pagePath(String path, int page) {
    return path + (path.contains("?") ? "&" : "?") + "per_page=100&page=" + page;
  }

  private long number(JsonNode node, String field) {
    if (node == null
        || !node.hasNonNull(field)
        || !node.get(field).isIntegralNumber()
        || node.get(field).asLong() < 0) throw new IncompleteObservationException();
    return node.get(field).asLong();
  }
}
