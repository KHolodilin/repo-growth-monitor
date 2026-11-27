package com.kholodilin.repogrowth.collection.service;

import com.kholodilin.collector.contracts.CollectorProtocol.*;
import com.kholodilin.repogrowth.catalog.model.OwnerType;
import com.kholodilin.repogrowth.catalog.model.RepositoryHealthFacts;
import com.kholodilin.repogrowth.catalog.repository.GitHubOwnerJdbcRepository;
import com.kholodilin.repogrowth.catalog.repository.RepositoryJdbcRepository;
import com.kholodilin.repogrowth.catalog.service.RepositoryHealthEvaluator;
import com.kholodilin.repogrowth.events.detect.*;
import com.kholodilin.repogrowth.events.detect.GitHubActivitySnapshot;
import com.kholodilin.repogrowth.events.detect.GrowthEventDetections;
import com.kholodilin.repogrowth.events.model.*;
import com.kholodilin.repogrowth.events.model.GrowthEventCatalog;
import com.kholodilin.repogrowth.events.repository.*;
import com.kholodilin.repogrowth.events.repository.GrowthEventJdbcRepository;
import com.kholodilin.repogrowth.events.repository.GrowthEventStateJdbcRepository;
import com.kholodilin.repogrowth.collection.integration.github.*;
import com.kholodilin.repogrowth.collection.integration.github.GitHubCommitResponse;
import com.kholodilin.repogrowth.collection.integration.github.GitHubCommunityProfileResponse;
import com.kholodilin.repogrowth.collection.integration.github.GitHubContributorItem;
import com.kholodilin.repogrowth.collection.integration.github.GitHubIssueItem;
import com.kholodilin.repogrowth.collection.integration.github.GitHubPullItem;
import com.kholodilin.repogrowth.collection.integration.github.GitHubReadmeResponse;
import com.kholodilin.repogrowth.collection.integration.github.GitHubReleaseItem;
import com.kholodilin.repogrowth.collection.integration.github.GitHubRepositoryResponse;
import com.kholodilin.repogrowth.collection.integration.github.GitHubSearchItem;
import com.kholodilin.repogrowth.search.repository.*;
import com.kholodilin.repogrowth.search.repository.SearchResultJdbcRepository;
import com.kholodilin.repogrowth.search.repository.SearchRunJdbcRepository;
import com.kholodilin.repogrowth.search.service.RepositoryEnricher;
import com.kholodilin.repogrowth.topics.model.TopicResult;
import com.kholodilin.repogrowth.topics.repository.*;
import com.kholodilin.repogrowth.topics.repository.TopicResultJdbcRepository;
import com.kholodilin.repogrowth.topics.repository.TopicRunJdbcRepository;
import com.kholodilin.repogrowth.topics.service.TopicWatchSync;
import com.kholodilin.repogrowth.collection.repository.CollectorHistoryJdbcRepository;
import com.kholodilin.repogrowth.stats.repository.StatsJdbcRepository;
import com.kholodilin.repogrowth.traffic.repository.TrafficJdbcRepository;
import com.kholodilin.repogrowth.traffic.service.ServicePathClassifier;
import java.time.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Product-side interpretation only. Called inside the receipt's owning product transaction. */
@Component
@ConditionalOnProperty(name = "rgm.orchestration.enabled", havingValue = "true")
public class CollectorHistoryApplier {
  @org.springframework.beans.factory.annotation.Autowired
  private com.kholodilin.repogrowth.administration.service.PublicationService publication;

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  private com.kholodilin.repogrowth.credentials.service.CredentialVault credentialVault;

  private final CollectorHistoryJdbcRepository history;
  private final JsonMapper json;
  private final RepositoryJdbcRepository repositories;
  private final GitHubOwnerJdbcRepository owners;
  private final TrafficJdbcRepository traffic;
  private final StatsJdbcRepository stats;
  private final TopicWatchSync watches;
  private final ServicePathClassifier paths;
  private final GrowthEventStateJdbcRepository eventState;
  private final GrowthEventJdbcRepository events;
  private final SearchRunJdbcRepository searchRuns;
  private final SearchResultJdbcRepository searchResults;
  private final RepositoryEnricher enricher;
  private final TopicRunJdbcRepository topicRuns;
  private final TopicResultJdbcRepository topicResults;

  public CollectorHistoryApplier(
      CollectorHistoryJdbcRepository history,
      JsonMapper json,
      RepositoryJdbcRepository repositories,
      GitHubOwnerJdbcRepository owners,
      TrafficJdbcRepository traffic,
      StatsJdbcRepository stats,
      TopicWatchSync watches,
      ServicePathClassifier paths,
      GrowthEventStateJdbcRepository eventState,
      GrowthEventJdbcRepository events,
      SearchRunJdbcRepository searchRuns,
      SearchResultJdbcRepository searchResults,
      RepositoryEnricher enricher,
      TopicRunJdbcRepository topicRuns,
      TopicResultJdbcRepository topicResults) {
    this.history = history;
    this.json = json;
    this.repositories = repositories;
    this.owners = owners;
    this.traffic = traffic;
    this.stats = stats;
    this.watches = watches;
    this.paths = paths;
    this.eventState = eventState;
    this.events = events;
    this.searchRuns = searchRuns;
    this.searchResults = searchResults;
    this.enricher = enricher;
    this.topicRuns = topicRuns;
    this.topicResults = topicResults;
  }

  private <T> T read(Snapshot snapshot, String key, Class<T> type) {
    String raw = snapshot.metadata().get(key);
    return raw == null ? null : json.readValue(raw, type);
  }

  private <T> List<T> list(Snapshot snapshot, String key, Class<T> type) {
    String raw = snapshot.metadata().get(key);
    if (raw == null) return List.of();
    var array = json.readTree(raw);
    List<T> values = new ArrayList<>();
    for (var value : array) values.add(json.treeToValue(value, type));
    return values;
  }

  private Instant commit(Snapshot snapshot, String key) {
    var items = list(snapshot, key, GitHubCommitResponse.class);
    return items.isEmpty() ? null : items.getFirst().committedAt();
  }

  private int metric(Snapshot snapshot, Metric metric, int fallback) {
    Long value = snapshot.metrics().get(metric);
    return value == null ? fallback : Math.toIntExact(value);
  }

  public void apply(Map<String, Object> task, JobRequest job, ResultDelivery result) {
    Long repositoryId =
        task.get("repository_id") == null ? null : ((Number) task.get("repository_id")).longValue();
    long taskId = ((Number) task.get("id")).longValue();
    if (result.status() == JobState.FAILED && job.type() == TaskType.DISCOVER_REPOSITORIES) return;
    if (result.status() == JobState.FAILED) {
      long repository = Objects.requireNonNull(repositoryId, "repository_id");
      if (result.failure().code() == ErrorCode.PERMISSION_DENIED
          && Set.of(TaskType.TRAFFIC, TaskType.REFERRERS, TaskType.POPULAR_PATHS)
              .contains(job.type())) repositories.setTrafficPermissionMissing(repository, true);
      legacy(taskId, repository, job, result);
      return;
    }
    Snapshot snapshot = result.snapshot();
    switch (job.type()) {
      case DISCOVER_REPOSITORIES -> discover(job, snapshot);
      case STATS -> stats(requireRepository(repositoryId), job, snapshot);
      case TRAFFIC -> {
        long repository = requireRepository(repositoryId);
        for (var day : snapshot.traffic()) {
          if (day.views() != null
              && day.visitors() != null
              && day.clones() != null
              && day.uniqueCloners() != null)
            traffic.upsertDaily(
                repository,
                day.date(),
                Math.toIntExact(day.views()),
                Math.toIntExact(day.visitors()),
                Math.toIntExact(day.clones()),
                Math.toIntExact(day.uniqueCloners()));
          else
            traffic.upsertDailyPartial(
                repository,
                day.date(),
                day.views(),
                day.visitors(),
                day.clones(),
                day.uniqueCloners());
        }
        repositories.setTrafficPermissionMissing(repository, false);
      }
      case REFERRERS, POPULAR_PATHS -> {
        long repository = requireRepository(repositoryId);
        LocalDate date = snapshot.observedAt().atZone(ZoneOffset.UTC).toLocalDate();
        if (job.type() == TaskType.REFERRERS) traffic.deleteReferrerSnapshot(repository, date);
        else traffic.deletePathSnapshot(repository, date);
        for (var row : snapshot.sources())
          if (job.type() == TaskType.REFERRERS)
            traffic.insertReferrers(
                repository,
                date,
                snapshot.observedAt(),
                row.key(),
                Math.toIntExact(row.views()),
                Math.toIntExact(row.visitors()));
          else
            traffic.insertPath(
                repository,
                date,
                snapshot.observedAt(),
                row.key(),
                row.title(),
                Math.toIntExact(row.views()),
                Math.toIntExact(row.visitors()),
                paths.isServicePath(row.key()));
        history.upsertSnapshotHeader(
            repository, job.type().name(), date, snapshot.observedAt());
        repositories.setTrafficPermissionMissing(repository, false);
      }
      case GROWTH_EVENTS -> growth(requireRepository(repositoryId), task, job, snapshot);
      case SEARCH_QUERY, TOPIC_WATCH ->
          rank(
              taskId,
              requireRepository(repositoryId),
              job,
              snapshot,
              (String) task.get("target_key"));
    }
    if (job.type() != TaskType.DISCOVER_REPOSITORIES
        && job.type() != TaskType.SEARCH_QUERY
        && job.type() != TaskType.TOPIC_WATCH)
      legacy(taskId, requireRepository(repositoryId), job, result);
  }

  private static long requireRepository(Long repositoryId) {
    return Objects.requireNonNull(repositoryId, "repository_id");
  }

  private void discover(JobRequest job, Snapshot snapshot) {
    for (var remote : list(snapshot, "repositories", GitHubRepositoryResponse.class)) {
      if (remote.owner() == null
          || remote.owner().login() == null
          || remote.owner().login().isBlank())
        throw new IllegalArgumentException("Discovery owner identity missing");
      if (job.repository() != null
          && !remote.owner().login().equalsIgnoreCase(job.repository().owner()))
        throw new IllegalArgumentException("Discovery owner identity mismatch");
      OwnerType ownerType =
          "Organization".equalsIgnoreCase(remote.owner().type())
              ? OwnerType.ORGANIZATION
              : OwnerType.USER;
      long owner =
          owners
              .upsert(
                  remote.owner().id(),
                  remote.owner().login(),
                  ownerType,
                  remote.owner().avatarUrl(),
                  remote.owner().htmlUrl())
              .id();
      var existing = repositories.findByGithubId(remote.id());
      // Keep the stored visibility until PublicationService.verify() accepts this observation.
      // Writing remote visibility here would let a stale PUBLIC discovery reopen a newer PRIVATE
      // verification.
      String visibility =
          existing.map(value -> value.visibility()).orElse(remote.resolvedVisibility());
      var stored =
          repositories.upsertKeepingTracking(
              new com.kholodilin.repogrowth.catalog.model.Repository(
                  null,
                  remote.id(),
                  owner,
                  remote.name(),
                  remote.fullName(),
                  remote.description(),
                  visibility,
                  remote.defaultBranch(),
                  remote.language(),
                  remote.fork(),
                  remote.archived(),
                  remote.stargazersCount(),
                  remote.watchers(),
                  remote.forksCount(),
                  remote.openIssuesCount(),
                  existing.map(value -> value.contributors()).orElse(0),
                  false,
                  remote.createdAt(),
                  remote.updatedAt(),
                  remote.pushedAt(),
                  null,
                  null,
                  null,
                  null,
                  null));
      publication.metadata(stored.id(), remote, snapshot.observedAt());
      repositories.markAccountAccessible(stored.id());
      repositories.replaceTopics(stored.id(), remote.topicsOrEmpty());
      if (credentialVault != null && job.authorization() != null)
        credentialVault.bind(stored.id(), job.authorization().credentialRef());
    }
  }

  private void stats(long id, JobRequest job, Snapshot snapshot) {
    var current = repositories.findById(id).orElseThrow();
    var remote =
        Objects.requireNonNull(read(snapshot, "repository", GitHubRepositoryResponse.class));
    if (remote.id() != current.githubId())
      throw new IllegalArgumentException("Source repository identity mismatch");
    publication.metadata(id, remote, snapshot.observedAt());
    int contributors = metric(snapshot, Metric.CONTRIBUTORS, current.contributors());
    int watchers = metric(snapshot, Metric.WATCHERS, current.watchers());
    repositories.updateStats(
        id,
        remote.description(),
        remote.stargazersCount(),
        watchers,
        remote.forksCount(),
        remote.openIssuesCount(),
        contributors,
        remote.updatedAt(),
        remote.pushedAt(),
        commit(snapshot, "latestCommits"),
        snapshot.observedAt(),
        remote.archived());
    repositories.replaceTopics(id, remote.topicsOrEmpty());
    watches.sync(id);
    var readme = read(snapshot, "readme", GitHubReadmeResponse.class);
    String text = readme == null ? null : readme.decodedText();
    var community = read(snapshot, "community", GitHubCommunityProfileResponse.class);
    var files =
        community == null
            ? GitHubCommunityProfileResponse.GitHubCommunityFiles.empty()
            : community.filesOrEmpty();
    var previous = repositories.findHealth(id).orElse(RepositoryHealthFacts.empty());
    var preview = read(snapshot, "socialPreview", tools.jackson.databind.JsonNode.class);
    boolean custom =
        preview != null && preview.hasNonNull("usesCustomOpenGraphImage")
            ? preview.get("usesCustomOpenGraphImage").asBoolean()
            : previous.hasSocialPreview();
    repositories.upsertHealth(
        id,
        new RepositoryHealthFacts(
            remote.hasHomepage() ? remote.homepage().trim() : null,
            files.hasReadme() || text != null,
            RepositoryHealthEvaluator.readmeHasH1(text),
            RepositoryHealthEvaluator.readmeHasName(text, current.name()),
            files.hasLicense() || remote.hasLicense(),
            community == null ? previous.hasCodeOfConduct() : files.hasCodeOfConduct(),
            community == null ? previous.hasContributing() : files.hasContributing(),
            Boolean.parseBoolean(
                snapshot
                    .metadata()
                    .getOrDefault(
                        "securityPolicy", Boolean.toString(previous.hasSecurityPolicy()))),
            files.hasIssueTemplate()
                || Boolean.parseBoolean(snapshot.metadata().getOrDefault("issueTemplate", "false")),
            community == null ? previous.hasPullRequestTemplate() : files.hasPullRequestTemplate(),
            custom));
    if ("true".equals(snapshot.metadata().get("releaseFetched"))) {
      var release = read(snapshot, "latestRelease", GitHubReleaseItem.class);
      repositories.updateLastReleaseAt(id, release == null ? null : release.timestamp());
    }
    stats.upsertDailyStats(
        id,
        job.businessDate(),
        remote.stargazersCount(),
        watchers,
        remote.forksCount(),
        remote.openIssuesCount(),
        contributors);
    if (snapshot.contributorLogins() != null)
      stats.replaceContributorLogins(id, job.businessDate(), snapshot.contributorLogins());
  }

  private void growth(long id, Map<String, Object> task, JobRequest job, Snapshot snapshot) {
    var remote =
        Objects.requireNonNull(read(snapshot, "repository", GitHubRepositoryResponse.class));
    var readme = read(snapshot, "readme", GitHubReadmeResponse.class);
    var preview = read(snapshot, "socialPreview", tools.jackson.databind.JsonNode.class);
    Boolean custom =
        preview != null && preview.hasNonNull("usesCustomOpenGraphImage")
            ? preview.get("usesCustomOpenGraphImage").asBoolean()
            : null;
    String previewUrl =
        preview != null && preview.hasNonNull("openGraphImageUrl")
            ? preview.get("openGraphImageUrl").asText()
            : null;
    var observation =
        new GitHubActivitySnapshot(
            remote,
            readme == null ? null : readme.decodedText(),
            readme == null ? null : readme.sha(),
            commit(snapshot, "readmeCommits"),
            list(snapshot, "issues", GitHubIssueItem.class),
            list(snapshot, "pulls", GitHubPullItem.class),
            list(snapshot, "releases", GitHubReleaseItem.class),
            list(snapshot, "contributors", GitHubContributorItem.class),
            custom,
            previewUrl);
    var previous = eventState.find(id);
    var settings =
        json.readTree(task.get("configuration_snapshot").toString()).path("growthEventSettings");
    for (var candidate :
        GrowthEventDetections.detect(
            previous, observation, job.repository().owner(), snapshot.observedAt()))
      if (settings.path(candidate.type()).asBoolean(false)) events.insertIgnore(id, candidate);
    if (observation.readmeCommittedAt() != null)
      events.pullEventAtBack(
          id, GrowthEventCatalog.README_SIGNIFICANTLY_CHANGED, observation.readmeCommittedAt());
    if (remote.updatedAt() != null)
      for (String type :
          List.of(
              GrowthEventCatalog.DESCRIPTION_CHANGED,
              GrowthEventCatalog.TOPICS_CHANGED,
              GrowthEventCatalog.HOMEPAGE_CHANGED))
        events.pullEventAtBack(id, type, remote.updatedAt());
    eventState.upsert(
        id, GrowthEventDetections.snapshot(previous, observation, job.repository().owner()));
  }

  private void rank(long task, long repository, JobRequest job, Snapshot snapshot, String target) {
    long targetId = CollectionRequestService.targetId(target);
    boolean topic = job.type() == TaskType.TOPIC_WATCH;
    // Existing history remains a daily compatibility view; no run rows are moved/deleted.
    long run;
    if (topic) {
      topicRuns.insertIgnore(targetId, repository, job.businessDate());
      run = topicRuns.find(targetId, job.businessDate()).orElseThrow().id();
    } else {
      searchRuns.insertIgnore(targetId, repository, job.businessDate());
      run = searchRuns.find(targetId, job.businessDate()).orElseThrow().id();
    }
    var raw = json.readTree(snapshot.metadata().getOrDefault("search", "{\"items\":[]}"));
    if (topic) {
      List<TopicResult> rows = new ArrayList<>();
      int pos = 1;
      for (var value : raw.path("items")) {
        var item = json.treeToValue(value, GitHubSearchItem.class);
        rows.add(
            new TopicResult(
                null,
                run,
                pos++,
                item.id(),
                item.fullName(),
                item.owner() == null ? "" : item.owner().login(),
                item.stargazersCount(),
                0,
                item.forksCount(),
                item.language(),
                item.description(),
                item.htmlUrl()));
      }
      topicResults.replaceAll(run, rows);
      topicRuns.markSuccess(
          run, Math.toIntExact(snapshot.totalResults()), snapshot.trackedPosition());
    } else {
      List<com.kholodilin.repogrowth.search.model.SearchResult> rows = new ArrayList<>();
      int pos = 1;
      for (var value : raw.path("items"))
        rows.add(
            enricher.fromSearchItem(run, pos++, json.treeToValue(value, GitHubSearchItem.class)));
      searchResults.replaceAll(run, rows);
      String sort = job.parameters().sort();
      String sortMode = sort == null || sort.isBlank() ? "stars" : sort;
      searchRuns.markSuccess(
          run, Math.toIntExact(snapshot.totalResults()), snapshot.trackedPosition(), sortMode);
      boolean enriched = rows.stream().allMatch(row -> row.metadataUpdatedAt() != null);
      searchRuns.markEnrichment(run, enriched ? "SUCCESS" : "PARTIAL");
    }
    boolean publicRows = job.parameters().publicOnly();
    for (var item : raw.path("items"))
      if (!item.hasNonNull("private")
          || !item.get("private").isBoolean()
          || item.get("private").asBoolean()) publicRows = false;
    if (topic) topicRuns.updateSnapshotScope(run, snapshot.observedAt(), publicRows);
    else searchRuns.updateSnapshotScope(run, snapshot.observedAt(), publicRows);
    history.insertHistoryMapping(task, job.type().name(), run);
  }

  private void legacy(long task, long repository, JobRequest job, ResultDelivery result) {
    boolean rank = job.type() == TaskType.SEARCH_QUERY || job.type() == TaskType.TOPIC_WATCH;
    if (rank) {
      long target = CollectionRequestService.targetId(history.targetKeyForTask(task));
      if (job.type() == TaskType.SEARCH_QUERY)
        searchRuns.upsertFailed(
            target,
            repository,
            job.businessDate(),
            result.failure().code().name(),
            result.failure().message());
      else
        topicRuns.upsertFailed(
            target,
            repository,
            job.businessDate(),
            result.failure().code().name(),
            result.failure().message());
      return;
    }
    long run = history.ensureLegacyRun(repository, job.businessDate());
    String type = job.type() == TaskType.STATS ? "REPOSITORY_STATS" : job.type().name(),
        state = result.status() == JobState.SUCCEEDED ? "SUCCESS" : "FAILED";
    history.upsertLegacyJob(
        run,
        repository,
        type,
        job.businessDate(),
        state,
        result.failure() == null ? null : result.failure().code().name(),
        result.failure() == null ? null : result.failure().message());
    history.refreshLegacyRun(run);
    history.insertHistoryMapping(task, job.type().name(), run);
  }
}
