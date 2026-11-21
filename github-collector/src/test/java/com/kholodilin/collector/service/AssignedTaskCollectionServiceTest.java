package com.kholodilin.collector.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.kholodilin.collector.contracts.CollectorProtocol.*;
import com.kholodilin.collector.integration.github.AssignedGithubSupport;
import com.kholodilin.collector.integration.github.AssignedGithubSupport.Incomplete;
import com.kholodilin.collector.integration.github.GitHubSourceReader;
import com.kholodilin.collector.model.CollectionContext;
import com.kholodilin.collector.model.ExecutionSettings;
import com.kholodilin.collector.repository.InMemoryObservationStore;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class AssignedTaskCollectionServiceTest {
  JsonMapper json = JsonMapper.builder().build();
  ExecutionSettings properties =
      new ExecutionSettings(
          URI.create("https://api.github.com"),
          Duration.ofMinutes(1),
          Duration.ofMillis(250),
          Duration.ofSeconds(10),
          5,
          5,
          5 * 1024 * 1024);
  AssignedGithubSupport github = new AssignedGithubSupport(properties, json);
  GitHubSourceReader reader = mock(GitHubSourceReader.class);
  Map<String, String> fixtures = new ConcurrentHashMap<>();
  List<String> fetchedKeys = Collections.synchronizedList(new ArrayList<>());
  InMemoryObservationStore store;
  ObservationCheckpoint checkpoint;
  AssignedTaskCollectionService collector;
  CollectionContext context;

  JobRequest job(TaskType type) {
    Parameters parameters =
        type == TaskType.SEARCH_QUERY
            ? new Parameters("spring", null, null, 50, null, null)
            : type == TaskType.TOPIC_WATCH
                ? new Parameters("topic:spring", "spring", null, 50, "stars", "desc")
                : new Parameters("spring", "spring", null, 50, "stars", "desc");
    return new JobRequest(
        1,
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        type,
        new RepositoryTarget("202", "owner", "repo"),
        LocalDate.now(),
        1,
        parameters,
        "rgm-results",
        Instant.now().plusSeconds(3600),
        new AuthorizationScope("ctx", "ref"));
  }

  @BeforeEach
  void stubGithub() {
    fixtures.clear();
    fetchedKeys.clear();
    store = new InMemoryObservationStore(5 * 1024 * 1024);
    checkpoint = new ObservationCheckpoint(store, reader, json);
    collector = new AssignedTaskCollectionService(checkpoint, github);
    context = new CollectionContext("rgm", 1);
    reset(reader);
    when(reader.fetch(
            anyString(), any(), anyString(), anyString(), anyString(), any(), anyBoolean()))
        .thenAnswer(invocation -> {
          String key = invocation.getArgument(2);
          fetchedKeys.add(key);
          String value = fixtures.get(key);
          if (value == null) {
            boolean optional = invocation.getArgument(6);
            if (optional) return json.readTree("null");
            throw new IllegalStateException("Missing fixture: " + key);
          }
          return json.readTree(value);
        });
  }

  @Test
  void statsPreserveRealZerosAndCollectExactContributorsAndHealthObservations() {
    fixtures.put(
        "repository",
        "{\"id\":202,\"stargazers_count\":0,\"forks_count\":0,\"subscribers_count\":0}");
    fixtures.put("contributors:1", "[]");
    fixtures.put("contributor-count", "{\"total\":0}");
    var snapshot = collector.collect(context, job(TaskType.STATS));
    assertThat(snapshot.metrics())
        .containsEntry(Metric.STARS, 0L)
        .containsEntry(Metric.CONTRIBUTORS, 0L);
    assertThat(snapshot.contributorLogins()).isEmpty();
    assertThat(snapshot.metadata()).containsKey("repository");
    assertThat(snapshot.metadata()).containsEntry("securityPolicy", "false");
  }

  @Test
  void trafficKeepsMissingObservationsDistinctFromZero() {
    fixtures.put(
        "views",
        "{\"views\":[{\"timestamp\":\"2026-11-01T00:00:00Z\",\"count\":0,\"uniques\":0}]}");
    fixtures.put("clones", "{\"clones\":[]}");
    var day = collector.collect(context, job(TaskType.TRAFFIC)).traffic().getFirst();
    assertThat(day.views()).isZero();
    assertThat(day.clones()).isNull();
  }

  @Test
  void referrersAndPathsRemainSeparateTypedSources() {
    long jobId = 10;
    for (var type : List.of(TaskType.REFERRERS, TaskType.POPULAR_PATHS)) {
      fixtures.clear();
      fetchedKeys.clear();
      fixtures.put(
          "sources",
          type == TaskType.REFERRERS
              ? "[{\"referrer\":\"example.com\",\"count\":12,\"uniques\":7}]"
              : "[{\"path\":\"/owner/repo/issues\",\"title\":\"Issues\",\"count\":12,\"uniques\":7}]");
      var source =
          collector.collect(new CollectionContext("rgm", jobId++), job(type)).sources().getFirst();
      assertThat(source.views()).isEqualTo(12);
      assertThat(source.visitors()).isEqualTo(7);
    }
  }

  @Test
  void growthProducesRawObservationsForProductOwnedDetection() {
    fixtures.put("repository", "{\"id\":202,\"stargazers_count\":10,\"forks_count\":2}");
    fixtures.put("contributors:1", "[]");
    fixtures.put("contributor-count", "{\"total\":0}");
    fixtures.put("issues:1", "[]");
    fixtures.put("pulls:1", "[]");
    fixtures.put("releases:1", "[]");
    var snapshot = collector.collect(context, job(TaskType.GROWTH_EVENTS));
    assertThat(snapshot.metadata())
        .containsKeys("repository", "issues", "pulls", "releases", "contributors");
    assertThat(snapshot.events()).isEmpty();
  }

  @Test
  void searchAndTopicsConfirmAnAbsentRankWithoutColdEnrichment() {
    long jobId = 20;
    for (var type : List.of(TaskType.SEARCH_QUERY, TaskType.TOPIC_WATCH)) {
      fixtures.clear();
      fetchedKeys.clear();
      fixtures.put("search", "{\"total_count\":0,\"incomplete_results\":false,\"items\":[]}");
      fixtures.put("search-confirmation", fixtures.get("search"));
      var snapshot = collector.collect(new CollectionContext("rgm", jobId++), job(type));
      assertThat(snapshot.totalResults()).isZero();
      assertThat(snapshot.trackedPosition()).isNull();
      assertThat(fetchedKeys).containsExactly("search", "search-confirmation");
      assertThat(snapshot.metadata()).containsEntry("enrichmentStatus", "DEFERRED");
    }
  }

  @Test
  void incompleteSearchNeverBecomesAFalseMissingRank() {
    var store = new InMemoryObservationStore(5 * 1024 * 1024);
    var local =
        new AssignedTaskCollectionService(new ObservationCheckpoint(store, reader, json), github);
    fixtures.put("search", "{\"total_count\":50,\"incomplete_results\":true,\"items\":[]}");
    assertThatThrownBy(() -> local.collect(context, job(TaskType.SEARCH_QUERY)))
        .isInstanceOf(Incomplete.class);
    assertThat(store.find(1, "search")).isEmpty();
  }

  @Test
  void discoveryOnlyReturnsAssignedOwnerAndNeverInventsAnIndependentSchedule() {
    fixtures.put(
        "accessible-repositories:1",
        "[{\"id\":1,\"owner\":{\"login\":\"owner\"}},{\"id\":2,\"owner\":{\"login\":\"another\"}}]");
    var snapshot = collector.collect(context, job(TaskType.DISCOVER_REPOSITORIES));
    assertThat(json.readTree(snapshot.metadata().get("repositories"))).hasSize(1);
    assertThat(fetchedKeys).containsExactly("accessible-repositories:1");
  }

  @Test
  void explicitlyPublishedSearchRequestsOnlyPublicResultsAndRequiresProof() {
    fixtures.put(
        "search",
        "{\"total_count\":1,\"items\":[{\"id\":202,\"private\":false,\"full_name\":\"owner/repo\",\"stargazers_count\":1,\"forks_count\":0}]}");
    var base = job(TaskType.SEARCH_QUERY);
    var published =
        new JobRequest(
            1,
            base.requestId(),
            base.jobId(),
            base.attemptId(),
            base.type(),
            base.repository(),
            base.businessDate(),
            1,
            new Parameters("spring", null, null, 50, null, null, true),
            base.destinationRef(),
            base.deadlineAt(),
            base.authorization());
    assertThat(collector.collect(context, published).metadata())
        .containsEntry("publicScopeVerified", "true");
    AtomicInteger searchCalls = new AtomicInteger();
    when(reader.fetch(
            anyString(),
            eq(published),
            eq("search"),
            anyString(),
            anyString(),
            any(),
            anyBoolean()))
        .thenAnswer(invocation -> {
          searchCalls.incrementAndGet();
          String path = invocation.getArgument(4);
          assertThat(path).contains("is%3Apublic");
          return json.readTree(fixtures.get("search"));
        });
    collector.collect(new CollectionContext("rgm", 2), published);
    assertThat(searchCalls.get()).isEqualTo(1);
  }

  @Test
  void searchUsesBestMatchWhileTopicsKeepExplicitSort() {
    fixtures.put(
        "search",
        "{\"total_count\":1,\"items\":[{\"id\":202,\"private\":false,\"full_name\":\"owner/repo\",\"stargazers_count\":1,\"forks_count\":0}]}");
    List<String> searchPaths = new ArrayList<>();
    when(reader.fetch(
            anyString(), any(), eq("search"), anyString(), anyString(), any(), anyBoolean()))
        .thenAnswer(invocation -> {
          searchPaths.add(invocation.getArgument(4));
          return json.readTree(fixtures.get("search"));
        });
    collector.collect(context, job(TaskType.SEARCH_QUERY));
    assertThat(searchPaths)
        .allMatch(path -> path.startsWith("/search/repositories?") && !path.contains("sort="));
    searchPaths.clear();
    collector.collect(new CollectionContext("rgm", 3), job(TaskType.TOPIC_WATCH));
    assertThat(searchPaths)
        .allMatch(path -> path.contains("sort=stars") && path.contains("order=desc"));
  }

  @Test
  void checkpointedRepositoryIsNotRefetchedWhenLaterObservationFailsAndRetries() {
    fixtures.put(
        "repository",
        "{\"id\":202,\"stargazers_count\":1,\"forks_count\":0,\"subscribers_count\":0}");
    fixtures.put("contributors:1", "[]");
    fixtures.put("contributor-count", "{\"total\":0}");
    AtomicInteger repositoryFetches = new AtomicInteger();
    when(reader.fetch(
            anyString(), any(), eq("repository"), anyString(), anyString(), any(), anyBoolean()))
        .thenAnswer(invocation -> {
          repositoryFetches.incrementAndGet();
          return json.readTree(fixtures.get("repository"));
        });
    when(reader.fetch(
            anyString(),
            any(),
            eq("social-preview"),
            anyString(),
            anyString(),
            any(),
            anyBoolean()))
        .thenThrow(new RuntimeException("graphql down"))
        .thenReturn(json.readTree("{\"data\":{\"repository\":{}}}"));

    assertThatThrownBy(() -> collector.collect(context, job(TaskType.STATS)))
        .hasMessageContaining("graphql down");
    var snapshot = collector.collect(context, job(TaskType.STATS));
    assertThat(snapshot.metrics()).containsEntry(Metric.STARS, 1L);
    assertThat(repositoryFetches.get()).isEqualTo(1);
  }
}
