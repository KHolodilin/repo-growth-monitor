package com.kholodilin.collector.service.collection;

import com.kholodilin.collector.contracts.CollectorProtocol.*;
import com.kholodilin.collector.integration.github.AssignedGithubClient;
import com.kholodilin.collector.model.CollectionContext;
import com.kholodilin.collector.model.IncompleteObservationException;
import com.kholodilin.collector.model.github.*;
import com.kholodilin.collector.service.ObservationCheckpoint;
import java.time.LocalDate;
import java.util.*;
import lombok.RequiredArgsConstructor;

/**
 * Sequences assigned collection with typed GitHub observations and per-key checkpoints. Does not
 * build GitHub URLs or parse GitHub JSON fields.
 */
@RequiredArgsConstructor
public final class AssignedTaskCollectionService {
  private final ObservationCheckpoint checkpoint;
  private final AssignedGithubClient github;

  public Snapshot collect(CollectionContext context, JobRequest job) {
    String origin = context.trustedOrigin();
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
        RepositoryObservation remote =
            checkpoint.get(
                context,
                "repository",
                RepositoryObservation.class,
                () -> github.repository(origin, job));
        keep(metadata, "repository", remote.rawJson());
        metrics.put(Metric.STARS, remote.stars());
        metrics.put(Metric.FORKS, remote.forks());
        if (remote.watchers() != null) metrics.put(Metric.WATCHERS, remote.watchers());
        List<ContributorPage.ContributorEntry> contributors = new ArrayList<>();
        List<String> contributorPages = new ArrayList<>();
        ContributorPage lastContributors = null;
        for (int page = 1; page <= github.maximumPages(); page++) {
          int current = page;
          ContributorPage slice =
              checkpoint.get(
                  context,
                  "contributors:" + current,
                  ContributorPage.class,
                  () -> github.contributorsPage(origin, job, current));
          lastContributors = slice;
          contributors.addAll(slice.items());
          contributorPages.add(slice.rawJson());
          if (!slice.hasNext()) break;
        }
        contributorLogins =
            contributors.stream()
                .map(ContributorPage.ContributorEntry::login)
                .filter(login -> login != null && !login.isBlank())
                .distinct()
                .sorted()
                .toList();
        ContributorTotal count =
            checkpoint.get(
                context,
                "contributor-count",
                ContributorTotal.class,
                () -> github.contributorCount(origin, job));
        metrics.put(Metric.CONTRIBUTORS, count.total());
        metadata.put("contributors", github.mergeJsonArrays(contributorPages));
        // Page budget exhausted while the last page still looks full: logins may be incomplete.
        if (lastContributors != null && lastContributors.hasNext()) contributorLogins = null;
        OpaqueDocument readme =
            checkpoint.get(
                context, "readme", OpaqueDocument.class, () -> github.readme(origin, job));
        keep(metadata, "readme", readme);
        SocialPreview preview =
            checkpoint.get(
                context,
                "social-preview",
                SocialPreview.class,
                () -> github.socialPreview(origin, job));
        if (preview.usable()) keep(metadata, "socialPreview", preview.repositoryJson());
        if (job.type() == TaskType.STATS) {
          keep(
              metadata,
              "community",
              checkpoint.get(
                  context, "community", OpaqueDocument.class, () -> github.community(origin, job)));
          keep(
              metadata,
              "latestCommits",
              checkpoint.get(
                  context,
                  "latest-commit",
                  OpaqueDocument.class,
                  () -> github.latestCommit(origin, job)));
          keep(
              metadata,
              "latestRelease",
              checkpoint.get(
                  context,
                  "latest-release",
                  OpaqueDocument.class,
                  () -> github.latestRelease(origin, job)));
          metadata.put("releaseFetched", "true");
          boolean security = false;
          for (String path : List.of(".github/SECURITY.md", "SECURITY.md", "docs/SECURITY.md")) {
            String key = "security:" + path;
            Boolean exists =
                checkpoint.get(
                    context,
                    key,
                    Boolean.class,
                    () -> github.contentExists(origin, job, key, path));
            if (Boolean.TRUE.equals(exists)) {
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
            String key = "issue-template:" + path;
            Boolean exists =
                checkpoint.get(
                    context,
                    key,
                    Boolean.class,
                    () -> github.contentExists(origin, job, key, path));
            if (Boolean.TRUE.equals(exists)) {
              issueTemplate = true;
              break;
            }
          }
          metadata.put("issueTemplate", Boolean.toString(issueTemplate));
        } else {
          metadata.put("issues", collectArrayPages(context, origin, job, "issues"));
          metadata.put("pulls", collectArrayPages(context, origin, job, "pulls"));
          metadata.put("releases", collectArrayPages(context, origin, job, "releases"));
          if (readme.present() && readme.name() != null)
            keep(
                metadata,
                "readmeCommits",
                checkpoint.get(
                    context,
                    "readme-commit",
                    OpaqueDocument.class,
                    () -> github.readmeCommits(origin, job, readme.name())));
        }
      }
      case DISCOVER_REPOSITORIES -> {
        List<String> pages = new ArrayList<>();
        int totalItems = 0;
        JsonArrayPage lastRepositories = null;
        for (int page = 1; page <= github.maximumPages(); page++) {
          int current = page;
          JsonArrayPage slice =
              checkpoint.get(
                  context,
                  "accessible-repositories:" + current,
                  JsonArrayPage.class,
                  () -> github.accessibleRepositoriesPage(origin, job, current));
          lastRepositories = slice;
          pages.add(slice.rawJson());
          totalItems += slice.size();
          if (!slice.hasNext()) break;
        }
        // Page budget exhausted while the last page still looks full: result may be incomplete.
        if (lastRepositories != null && lastRepositories.hasNext())
          throw new IncompleteObservationException();
        metadata.put(
            "repositories",
            github.ownedRepositoriesMetadata(
                new JsonArrayPage(github.mergeJsonArrays(pages), totalItems, false),
                job.repository().owner()));
      }
      case TRAFFIC -> {
        TrafficSeries views =
            checkpoint.get(context, "views", TrafficSeries.class, () -> github.views(origin, job));
        TrafficSeries clones =
            checkpoint.get(
                context, "clones", TrafficSeries.class, () -> github.clones(origin, job));
        traffic.addAll(mergeTraffic(views, clones));
      }
      case REFERRERS, POPULAR_PATHS -> {
        PopularSources popular =
            checkpoint.get(
                context,
                "sources",
                PopularSources.class,
                () ->
                    job.type() == TaskType.POPULAR_PATHS
                        ? github.popularPaths(origin, job)
                        : github.referrers(origin, job));
        for (var entry : popular.entries())
          sources.add(
              new SourceObservation(entry.key(), entry.title(), entry.count(), entry.uniques()));
      }
      case SEARCH_QUERY, TOPIC_WATCH -> {
        int limit = job.parameters().resultLimit() == null ? 50 : job.parameters().resultLimit();
        SearchObservation data =
            checkpoint.get(
                context,
                "search",
                SearchObservation.class,
                () -> github.search(origin, job, "search"));
        total = data.totalCount();
        position = data.trackedPosition();
        if (position == null) {
          if (data.incompleteResults() || data.items().size() < Math.min(limit, total)) {
            checkpoint.invalidate(context, "search");
            throw new IncompleteObservationException();
          }
          data =
              checkpoint.get(
                  context,
                  "search-confirmation",
                  SearchObservation.class,
                  () -> github.search(origin, job, "search-confirmation"));
          total = data.totalCount();
          position = data.trackedPosition();
          if (position == null
              && (data.incompleteResults() || data.items().size() < Math.min(limit, total))) {
            checkpoint.invalidate(context, "search-confirmation");
            throw new IncompleteObservationException();
          }
        }
        int rank = 1;
        for (var hit : data.items())
          rankings.add(
              new RankedRepository(hit.id(), hit.fullName(), rank++, hit.stars(), hit.forks()));
        metadata.put(
            "publicScopeVerified",
            Boolean.toString(job.parameters().publicOnly() && data.allPublic()));
        keep(metadata, "search", data.rawJson());
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

  private String collectArrayPages(
      CollectionContext context, String origin, JobRequest job, String prefix) {
    List<String> pages = new ArrayList<>();
    for (int page = 1; page <= github.maximumPages(); page++) {
      int current = page;
      JsonArrayPage slice =
          checkpoint.get(
              context,
              prefix + ":" + current,
              JsonArrayPage.class,
              () ->
                  switch (prefix) {
                    case "issues" -> github.issuesPage(origin, job, current);
                    case "pulls" -> github.pullsPage(origin, job, current);
                    case "releases" -> github.releasesPage(origin, job, current);
                    default -> throw new IllegalStateException(prefix);
                  });
      pages.add(slice.rawJson());
      if (!slice.hasNext()) break;
    }
    return github.mergeJsonArrays(pages);
  }

  private static List<TrafficDay> mergeTraffic(TrafficSeries views, TrafficSeries clones) {
    Map<LocalDate, Long[]> days = new TreeMap<>();
    for (var bucket : views.buckets()) {
      Long[] d = days.computeIfAbsent(bucket.date(), k -> new Long[4]);
      d[0] = bucket.count();
      d[1] = bucket.uniques();
    }
    for (var bucket : clones.buckets()) {
      Long[] d = days.computeIfAbsent(bucket.date(), k -> new Long[4]);
      d[2] = bucket.count();
      d[3] = bucket.uniques();
    }
    List<TrafficDay> traffic = new ArrayList<>();
    days.forEach((date, d) -> traffic.add(new TrafficDay(date, d[0], d[1], d[2], d[3])));
    return traffic;
  }

  private static void keep(Map<String, String> metadata, String key, OpaqueDocument document) {
    if (document != null && document.present()) keep(metadata, key, document.rawJson());
  }

  private static void keep(Map<String, String> metadata, String key, String rawJson) {
    if (rawJson != null && !rawJson.isBlank() && !"null".equals(rawJson))
      metadata.put(key, rawJson);
  }
}
