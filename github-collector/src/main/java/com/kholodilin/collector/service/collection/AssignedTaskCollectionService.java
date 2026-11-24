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

  /** Growth-history page family; {@link #checkpointPrefix} is the stable checkpoint key prefix. */
  private enum ArrayHistory {
    ISSUES("issues"),
    PULLS("pulls"),
    RELEASES("releases");

    private final String checkpointPrefix;

    ArrayHistory(String checkpointPrefix) {
      this.checkpointPrefix = checkpointPrefix;
    }

    String checkpointPrefix() {
      return checkpointPrefix;
    }
  }

  /** Accumulates Snapshot pieces while a scenario runs. */
  private static final class Draft {
    final Map<String, String> metadata = new TreeMap<>();
    final Map<Metric, Long> metrics = new EnumMap<>(Metric.class);
    List<String> contributorLogins;
    final List<TrafficDay> traffic = new ArrayList<>();
    final List<SourceObservation> sources = new ArrayList<>();
    final List<RankedRepository> rankings = new ArrayList<>();
    Long totalResults;
    Integer trackedPosition;
  }

  /** Per-day traffic cells; null means missing observation (not zero). */
  private static final class TrafficAcc {
    Long views;
    Long uniqueViews;
    Long clones;
    Long uniqueClones;
  }

  public Snapshot collect(CollectionContext context, JobRequest job) {
    String origin = context.trustedOrigin();
    Draft draft = new Draft();
    draft.metadata.put("sourceWindowPages", Integer.toString(github.maximumPages()));
    switch (job.type()) {
      case STATS -> collectStats(context, origin, job, draft);
      case GROWTH_EVENTS -> collectGrowthEvents(context, origin, job, draft);
      case DISCOVER_REPOSITORIES -> collectDiscovery(context, origin, job, draft);
      case TRAFFIC -> collectTraffic(context, origin, job, draft);
      case REFERRERS, POPULAR_PATHS -> collectPopularSources(context, origin, job, draft);
      case SEARCH_QUERY, TOPIC_WATCH -> collectSearch(context, origin, job, draft);
    }
    return new Snapshot(
        checkpoint.observedAt(context),
        true,
        draft.metrics,
        draft.contributorLogins,
        draft.traffic,
        draft.sources,
        draft.rankings,
        draft.totalResults,
        draft.trackedPosition,
        draft.metadata,
        List.of());
  }

  private void collectStats(CollectionContext context, String origin, JobRequest job, Draft draft) {
    collectRepositoryCore(context, origin, job, draft);
    keep(
        draft.metadata,
        "community",
        checkpoint.get(
            context, "community", OpaqueDocument.class, () -> github.community(origin, job)));
    keep(
        draft.metadata,
        "latestCommits",
        checkpoint.get(
            context,
            "latest-commit",
            OpaqueDocument.class,
            () -> github.latestCommit(origin, job)));
    keep(
        draft.metadata,
        "latestRelease",
        checkpoint.get(
            context,
            "latest-release",
            OpaqueDocument.class,
            () -> github.latestRelease(origin, job)));
    draft.metadata.put("releaseFetched", "true");
    draft.metadata.put(
        "securityPolicy",
        Boolean.toString(
            contentExistsAny(
                context,
                origin,
                job,
                "security:",
                List.of(".github/SECURITY.md", "SECURITY.md", "docs/SECURITY.md"))));
    draft.metadata.put(
        "issueTemplate",
        Boolean.toString(
            contentExistsAny(
                context,
                origin,
                job,
                "issue-template:",
                List.of(
                    ".github/ISSUE_TEMPLATE",
                    ".github/ISSUE_TEMPLATE.md",
                    "ISSUE_TEMPLATE.md",
                    "docs/ISSUE_TEMPLATE.md"))));
  }

  private void collectGrowthEvents(
      CollectionContext context, String origin, JobRequest job, Draft draft) {
    OpaqueDocument readme = collectRepositoryCore(context, origin, job, draft);
    draft.metadata.put("issues", collectArrayPages(context, origin, job, ArrayHistory.ISSUES));
    draft.metadata.put("pulls", collectArrayPages(context, origin, job, ArrayHistory.PULLS));
    draft.metadata.put("releases", collectArrayPages(context, origin, job, ArrayHistory.RELEASES));
    if (readme.present() && readme.name() != null)
      keep(
          draft.metadata,
          "readmeCommits",
          checkpoint.get(
              context,
              "readme-commit",
              OpaqueDocument.class,
              () -> github.readmeCommits(origin, job, readme.name())));
  }

  /** Shared repository / contributors / readme / social-preview for STATS and GROWTH_EVENTS. */
  private OpaqueDocument collectRepositoryCore(
      CollectionContext context, String origin, JobRequest job, Draft draft) {
    RepositoryObservation remote =
        checkpoint.get(
            context,
            "repository",
            RepositoryObservation.class,
            () -> github.repository(origin, job));
    keep(draft.metadata, "repository", remote.rawJson());
    draft.metrics.put(Metric.STARS, remote.stars());
    draft.metrics.put(Metric.FORKS, remote.forks());
    if (remote.watchers() != null) draft.metrics.put(Metric.WATCHERS, remote.watchers());
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
    draft.contributorLogins =
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
    draft.metrics.put(Metric.CONTRIBUTORS, count.total());
    draft.metadata.put("contributors", github.mergeJsonArrays(contributorPages));
    // Page budget exhausted while the last page still looks full: logins may be incomplete.
    if (lastContributors != null && lastContributors.hasNext()) draft.contributorLogins = null;
    OpaqueDocument readme =
        checkpoint.get(context, "readme", OpaqueDocument.class, () -> github.readme(origin, job));
    keep(draft.metadata, "readme", readme);
    SocialPreview preview =
        checkpoint.get(
            context,
            "social-preview",
            SocialPreview.class,
            () -> github.socialPreview(origin, job));
    if (preview.usable()) keep(draft.metadata, "socialPreview", preview.repositoryJson());
    return readme;
  }

  private void collectDiscovery(
      CollectionContext context, String origin, JobRequest job, Draft draft) {
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
    JsonArrayPage accessible = new JsonArrayPage(github.mergeJsonArrays(pages), totalItems, false);
    // No repository yet (empty install): publish everything the credential can see.
    draft.metadata.put(
        "repositories",
        job.repository() == null
            ? github.accessibleRepositoriesMetadata(accessible)
            : github.ownedRepositoriesMetadata(accessible, job.repository().owner()));
  }

  private void collectTraffic(
      CollectionContext context, String origin, JobRequest job, Draft draft) {
    TrafficSeries views =
        checkpoint.get(context, "views", TrafficSeries.class, () -> github.views(origin, job));
    TrafficSeries clones =
        checkpoint.get(context, "clones", TrafficSeries.class, () -> github.clones(origin, job));
    draft.traffic.addAll(mergeTraffic(views, clones));
  }

  private void collectPopularSources(
      CollectionContext context, String origin, JobRequest job, Draft draft) {
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
      draft.sources.add(
          new SourceObservation(entry.key(), entry.title(), entry.count(), entry.uniques()));
  }

  private void collectSearch(
      CollectionContext context, String origin, JobRequest job, Draft draft) {
    int limit = job.parameters().resultLimit() == null ? 50 : job.parameters().resultLimit();
    SearchObservation data =
        checkpoint.get(
            context, "search", SearchObservation.class, () -> github.search(origin, job, "search"));
    draft.totalResults = data.totalCount();
    draft.trackedPosition = data.trackedPosition();
    if (draft.trackedPosition == null) {
      if (data.incompleteResults() || data.items().size() < Math.min(limit, draft.totalResults)) {
        checkpoint.invalidate(context, "search");
        throw new IncompleteObservationException();
      }
      data =
          checkpoint.get(
              context,
              "search-confirmation",
              SearchObservation.class,
              () -> github.search(origin, job, "search-confirmation"));
      draft.totalResults = data.totalCount();
      draft.trackedPosition = data.trackedPosition();
      if (draft.trackedPosition == null
          && (data.incompleteResults()
              || data.items().size() < Math.min(limit, draft.totalResults))) {
        checkpoint.invalidate(context, "search-confirmation");
        throw new IncompleteObservationException();
      }
    }
    int rank = 1;
    for (var hit : data.items())
      draft.rankings.add(
          new RankedRepository(hit.id(), hit.fullName(), rank++, hit.stars(), hit.forks()));
    draft.metadata.put(
        "publicScopeVerified", Boolean.toString(job.parameters().publicOnly() && data.allPublic()));
    keep(draft.metadata, "search", data.rawJson());
    draft.metadata.put("enrichmentStatus", "DEFERRED");
  }

  private boolean contentExistsAny(
      CollectionContext context,
      String origin,
      JobRequest job,
      String keyPrefix,
      List<String> paths) {
    for (String path : paths) {
      String key = keyPrefix + path;
      Boolean exists =
          checkpoint.get(
              context, key, Boolean.class, () -> github.contentExists(origin, job, key, path));
      if (Boolean.TRUE.equals(exists)) return true;
    }
    return false;
  }

  private String collectArrayPages(
      CollectionContext context, String origin, JobRequest job, ArrayHistory history) {
    List<String> pages = new ArrayList<>();
    for (int page = 1; page <= github.maximumPages(); page++) {
      int current = page;
      String key = history.checkpointPrefix() + ":" + current;
      JsonArrayPage slice =
          checkpoint.get(
              context,
              key,
              JsonArrayPage.class,
              () ->
                  switch (history) {
                    case ISSUES -> github.issuesPage(origin, job, current);
                    case PULLS -> github.pullsPage(origin, job, current);
                    case RELEASES -> github.releasesPage(origin, job, current);
                  });
      pages.add(slice.rawJson());
      if (!slice.hasNext()) break;
    }
    return github.mergeJsonArrays(pages);
  }

  private static List<TrafficDay> mergeTraffic(TrafficSeries views, TrafficSeries clones) {
    Map<LocalDate, TrafficAcc> days = new TreeMap<>();
    for (var bucket : views.buckets()) {
      TrafficAcc day = days.computeIfAbsent(bucket.date(), k -> new TrafficAcc());
      day.views = bucket.count();
      day.uniqueViews = bucket.uniques();
    }
    for (var bucket : clones.buckets()) {
      TrafficAcc day = days.computeIfAbsent(bucket.date(), k -> new TrafficAcc());
      day.clones = bucket.count();
      day.uniqueClones = bucket.uniques();
    }
    List<TrafficDay> traffic = new ArrayList<>();
    days.forEach((date, day) ->
        traffic.add(
            new TrafficDay(date, day.views, day.uniqueViews, day.clones, day.uniqueClones)));
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
