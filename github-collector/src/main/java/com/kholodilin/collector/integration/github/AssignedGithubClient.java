package com.kholodilin.collector.integration.github;

import com.kholodilin.collector.contracts.CollectorProtocol.JobRequest;
import com.kholodilin.collector.model.ExecutionSettings;
import com.kholodilin.collector.model.github.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.util.UriUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Typed GitHub observation operations for assigned collection. Owns URL/GraphQL request formation
 * and delegates JSON→observation mapping; no checkpoint.
 */
@RequiredArgsConstructor
public final class AssignedGithubClient {
  /** GitHub list page size used by assigned collection ({@code per_page}). */
  static final int PAGE_SIZE = 100;

  private final GitHubSourceReader source;
  private final ExecutionSettings settings;
  private final GitHubObservationMapper observations;
  private final JsonMapper json;

  public int maximumPages() {
    return settings.maximumPages();
  }

  public RepositoryObservation repository(String origin, JobRequest job) {
    return observations.repository(get(origin, job, "repository", repo(job), false));
  }

  public ContributorPage contributorsPage(String origin, JobRequest job, int page) {
    return observations.contributors(
        get(
            origin,
            job,
            "contributors:" + page,
            pagePath(repo(job) + "/contributors?anon=true", page),
            false),
        PAGE_SIZE);
  }

  public ContributorTotal contributorCount(String origin, JobRequest job) {
    return observations.contributorTotal(
        get(
            origin,
            job,
            "contributor-count",
            repo(job) + "/contributors?per_page=1&anon=true",
            false));
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
    return observations.socialPreview(
        source.fetch(origin, job, "social-preview", "POST", "/graphql", body, true));
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
    return observations.contentPresent(
        get(origin, job, key, repo(job) + "/contents/" + relativePath, true));
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

  public String accessibleRepositoriesMetadata(JsonArrayPage page) {
    return observations.accessibleRepositoriesMetadata(page);
  }

  public String ownedRepositoriesMetadata(JsonArrayPage page, String ownerLogin) {
    return observations.ownedRepositoriesMetadata(page, ownerLogin);
  }

  /** Concatenate opaque JSON array pages into one array string for Snapshot metadata. */
  public String mergeJsonArrays(List<String> pages) {
    return observations.mergeJsonArrays(pages);
  }

  public TrafficSeries views(String origin, JobRequest job) {
    return observations.views(get(origin, job, "views", repo(job) + "/traffic/views", false));
  }

  public TrafficSeries clones(String origin, JobRequest job) {
    return observations.clones(get(origin, job, "clones", repo(job) + "/traffic/clones", false));
  }

  public PopularSources referrers(String origin, JobRequest job) {
    return observations.referrers(
        get(origin, job, "sources", repo(job) + "/traffic/popular/referrers", false));
  }

  public PopularSources popularPaths(String origin, JobRequest job) {
    return observations.popularPaths(
        get(origin, job, "sources", repo(job) + "/traffic/popular/paths", false));
  }

  public SearchObservation search(String origin, JobRequest job, String checkpointKey) {
    return observations.search(
        get(origin, job, checkpointKey, searchPath(job), false), job.repository().githubId());
  }

  private JsonArrayPage arrayPage(String origin, JobRequest job, String key, String path) {
    return observations.arrayPage(get(origin, job, key, path, false), PAGE_SIZE);
  }

  private OpaqueDocument optionalDocument(String origin, JobRequest job, String key, String path) {
    return observations.optionalDocument(get(origin, job, key, path, true));
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
    return path + (path.contains("?") ? "&" : "?") + "per_page=" + PAGE_SIZE + "&page=" + page;
  }
}
