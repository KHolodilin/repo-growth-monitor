package com.kholodilin.collector.service;

import com.kholodilin.collector.contracts.CollectorProtocol.*;
import com.kholodilin.collector.integration.github.AssignedGithubSupport;
import com.kholodilin.collector.integration.github.AssignedGithubSupport.Incomplete;
import com.kholodilin.collector.model.CollectionContext;
import java.util.*;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;

/**
 * Sequences assigned collection: checkpointed observation access, GitHub misses via integration
 * helpers, and Snapshot assembly.
 */
@RequiredArgsConstructor
public final class AssignedTaskCollectionService {
  private final ObservationCheckpoint checkpoint;
  private final AssignedGithubSupport github;

  private JsonNode get(
      CollectionContext context, JobRequest job, String key, String path, boolean optional) {
    return checkpoint.read(context, job, key, "GET", path, null, optional);
  }

  private List<JsonNode> paged(CollectionContext context, JobRequest job, String key, String path) {
    List<JsonNode> items = new ArrayList<>();
    for (int page = 1; page <= github.maximumPages(); page++) {
      JsonNode data = get(context, job, key + ":" + page, github.pagePath(path, page), false);
      if (!data.isArray()) throw new Incomplete();
      data.forEach(items::add);
      if (data.size() < 100) return items;
    }
    return items;
  }

  public Snapshot collect(CollectionContext context, JobRequest job) {
    String repo = github.repositoryPath(job);
    Map<String, String> metadata = new TreeMap<>();
    metadata.put("sourceWindowPages", Integer.toString(github.maximumPages()));
    Map<Metric, Long> metrics = new EnumMap<>(Metric.class);
    List<String> contributorLogins = null;
    List<TrafficDay> traffic = new ArrayList<>();
    List<SourceObservation> sources = new ArrayList<>();
    List<RankedRepository> rankings = new ArrayList<>();
    Long total = null;
    Integer position = null;
    switch (job.type()) {
      case STATS, GROWTH_EVENTS -> {
        JsonNode remote = get(context, job, "repository", repo, false);
        github.keep(metadata, "repository", remote);
        metrics.put(Metric.STARS, github.number(remote, "stargazers_count"));
        metrics.put(Metric.FORKS, github.number(remote, "forks_count"));
        if (remote.hasNonNull("subscribers_count"))
          metrics.put(Metric.WATCHERS, github.number(remote, "subscribers_count"));
        var contributors = paged(context, job, "contributors", repo + "/contributors?anon=true");
        contributorLogins = github.contributorLogins(contributors);
        JsonNode count =
            get(
                context,
                job,
                "contributor-count",
                repo + "/contributors?per_page=1&anon=true",
                false);
        metrics.put(Metric.CONTRIBUTORS, github.number(count, "total"));
        metadata.put("contributors", github.writeArray(contributors));
        if (contributors.size() >= github.maximumPages() * 100) contributorLogins = null;
        JsonNode readme = get(context, job, "readme", repo + "/readme", true);
        github.keep(metadata, "readme", readme);
        JsonNode preview =
            checkpoint.read(
                context,
                job,
                "social-preview",
                "POST",
                "/graphql",
                github.socialPreviewBody(job),
                true);
        if (!preview.hasNonNull("errors"))
          github.keep(metadata, "socialPreview", preview.path("data").path("repository"));
        if (job.type() == TaskType.STATS) {
          github.keep(
              metadata,
              "community",
              get(context, job, "community", repo + "/community/profile", true));
          github.keep(
              metadata,
              "latestCommits",
              get(context, job, "latest-commit", repo + "/commits?per_page=1", true));
          github.keep(
              metadata,
              "latestRelease",
              get(context, job, "latest-release", repo + "/releases/latest", true));
          metadata.put("releaseFetched", "true");
          boolean security = false;
          for (String path : List.of(".github/SECURITY.md", "SECURITY.md", "docs/SECURITY.md")) {
            if (!get(context, job, "security:" + path, repo + "/contents/" + path, true).isNull()) {
              security = true;
              break;
            }
          }
          metadata.put("securityPolicy", Boolean.toString(security));
          boolean issueTemplate = false;
          for (String path :
              List.of(
                  ".github/ISSUE_TEMPLATE",
                  ".github/ISSUE_TEMPLATE.md",
                  "ISSUE_TEMPLATE.md",
                  "docs/ISSUE_TEMPLATE.md")) {
            if (!get(context, job, "issue-template:" + path, repo + "/contents/" + path, true)
                .isNull()) {
              issueTemplate = true;
              break;
            }
          }
          metadata.put("issueTemplate", Boolean.toString(issueTemplate));
        } else {
          metadata.put(
              "issues",
              github.writeArray(
                  paged(
                      context,
                      job,
                      "issues",
                      repo + "/issues?state=all&sort=created&direction=desc")));
          metadata.put(
              "pulls",
              github.writeArray(
                  paged(
                      context,
                      job,
                      "pulls",
                      repo + "/pulls?state=all&sort=created&direction=desc")));
          metadata.put(
              "releases", github.writeArray(paged(context, job, "releases", repo + "/releases")));
          if (readme.hasNonNull("name"))
            github.keep(
                metadata,
                "readmeCommits",
                get(
                    context,
                    job,
                    "readme-commit",
                    github.readmeCommitPath(repo, readme.get("name").asText()),
                    true));
        }
      }
      case DISCOVER_REPOSITORIES -> {
        List<JsonNode> repositories =
            paged(
                context,
                job,
                "accessible-repositories",
                "/user/repos?affiliation=owner,collaborator,organization_member&sort=full_name");
        if (repositories.size() >= github.maximumPages() * 100) throw new Incomplete();
        github.keep(
            metadata,
            "repositories",
            github.ownedRepositories(repositories, job.repository().owner()));
      }
      case TRAFFIC -> {
        JsonNode views = get(context, job, "views", repo + "/traffic/views", false);
        JsonNode clones = get(context, job, "clones", repo + "/traffic/clones", false);
        traffic.addAll(github.trafficDays(views, clones));
      }
      case REFERRERS, POPULAR_PATHS -> {
        boolean paths = job.type() == TaskType.POPULAR_PATHS;
        JsonNode data =
            get(
                context,
                job,
                "sources",
                repo + "/traffic/popular/" + (paths ? "paths" : "referrers"),
                false);
        sources.addAll(github.sourceRows(data, paths));
      }
      case SEARCH_QUERY, TOPIC_WATCH -> {
        int limit = job.parameters().resultLimit() == null ? 50 : job.parameters().resultLimit();
        String path = github.searchPath(job);
        JsonNode data = get(context, job, "search", path, false);
        total = github.number(data, "total_count");
        position = github.findPosition(data.path("items"), job.repository().githubId());
        if (position == null) {
          if (data.path("incomplete_results").asBoolean(false)
              || data.path("items").size() < Math.min(limit, total)) {
            checkpoint.invalidate(context, "search");
            throw new Incomplete();
          }
          data = get(context, job, "search-confirmation", path, false);
          total = github.number(data, "total_count");
          position = github.findPosition(data.path("items"), job.repository().githubId());
          if (position == null
              && (data.path("incomplete_results").asBoolean(false)
                  || data.path("items").size() < Math.min(limit, total))) {
            checkpoint.invalidate(context, "search-confirmation");
            throw new Incomplete();
          }
        }
        rankings.addAll(github.rankings(data.path("items")));
        metadata.put(
            "publicScopeVerified",
            Boolean.toString(
                job.parameters().publicOnly() && github.allPublic(data.path("items"))));
        github.keep(metadata, "search", data);
        metadata.put("enrichmentStatus", "DEFERRED");
      }
    }
    return new Snapshot(
        checkpoint.observedAt(context),
        true,
        metrics,
        contributorLogins,
        traffic,
        sources,
        rankings,
        total,
        position,
        metadata,
        List.of());
  }
}
