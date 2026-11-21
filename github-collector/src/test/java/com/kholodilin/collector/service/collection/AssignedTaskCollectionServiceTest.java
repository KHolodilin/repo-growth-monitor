package com.kholodilin.collector.service.collection;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.kholodilin.collector.contracts.CollectorProtocol.*;
import com.kholodilin.collector.integration.github.AssignedGithubClient;
import com.kholodilin.collector.integration.github.GitHubSourceReader;
import com.kholodilin.collector.model.CollectionContext;
import com.kholodilin.collector.model.ExecutionSettings;
import com.kholodilin.collector.model.IncompleteObservationException;
import com.kholodilin.collector.repository.InMemoryObservationStore;
import com.kholodilin.collector.service.ObservationCheckpoint;
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
  GitHubSourceReader reader = mock(GitHubSourceReader.class);
  AssignedGithubClient github = new AssignedGithubClient(reader, properties, json);
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
    checkpoint = new ObservationCheckpoint(store, json);
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
    var local = new AssignedTaskCollectionService(new ObservationCheckpoint(store, json), github);
    fixtures.put("search", "{\"total_count\":50,\"incomplete_results\":true,\"items\":[]}");
    assertThatThrownBy(() -> local.collect(context, job(TaskType.SEARCH_QUERY)))
        .isInstanceOf(IncompleteObservationException.class);
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

  private static String fullContributorPage(int fromId) {
    StringBuilder page = new StringBuilder("[");
    for (int i = 0; i < 100; i++) {
      if (i > 0) page.append(',');
      int id = fromId + i;
      page.append("{\"login\":\"u").append(id).append("\",\"id\":").append(id).append('}');
    }
    return page.append(']').toString();
  }

  private AssignedTaskCollectionService collectorWithMaxPages(int maximumPages) {
    var settings =
        new ExecutionSettings(
            URI.create("https://api.github.com"),
            Duration.ofMinutes(1),
            Duration.ofMillis(250),
            Duration.ofSeconds(10),
            5,
            maximumPages,
            5 * 1024 * 1024);
    return new AssignedTaskCollectionService(
        checkpoint, new AssignedGithubClient(reader, settings, json));
  }

  @Test
  void shortContributorPageStopsBeforePageBudget() {
    fixtures.put(
        "repository",
        "{\"id\":202,\"stargazers_count\":1,\"forks_count\":0,\"subscribers_count\":0}");
    fixtures.put("contributors:1", "[{\"login\":\"only\",\"id\":1}]");
    fixtures.put("contributor-count", "{\"total\":1}");
    var snapshot = collector.collect(context, job(TaskType.STATS));
    assertThat(snapshot.contributorLogins()).containsExactly("only");
    assertThat(fetchedKeys).doesNotContain("contributors:2");
  }

  @Test
  void fullContributorPageContinuesWithinPageBudget() {
    fixtures.put(
        "repository",
        "{\"id\":202,\"stargazers_count\":1,\"forks_count\":0,\"subscribers_count\":0}");
    fixtures.put("contributors:1", fullContributorPage(0));
    fixtures.put("contributors:2", "[{\"login\":\"tail\",\"id\":200}]");
    fixtures.put("contributor-count", "{\"total\":101}");
    var snapshot = collector.collect(context, job(TaskType.STATS));
    assertThat(snapshot.contributorLogins()).contains("u0", "tail");
    assertThat(fetchedKeys).contains("contributors:1", "contributors:2");
  }

  @Test
  void exhaustedContributorBudgetWithFullLastPageDropsLogins() {
    var local = collectorWithMaxPages(1);
    fixtures.put(
        "repository",
        "{\"id\":202,\"stargazers_count\":1,\"forks_count\":0,\"subscribers_count\":0}");
    fixtures.put("contributors:1", fullContributorPage(0));
    fixtures.put("contributor-count", "{\"total\":100}");
    var snapshot = local.collect(context, job(TaskType.STATS));
    assertThat(snapshot.contributorLogins()).isNull();
    assertThat(snapshot.metadata()).containsKey("contributors");
    assertThat(fetchedKeys).doesNotContain("contributors:2");
  }

  @Test
  void exhaustedContributorBudgetWithShortLastPageKeepsLogins() {
    var local = collectorWithMaxPages(1);
    fixtures.put(
        "repository",
        "{\"id\":202,\"stargazers_count\":1,\"forks_count\":0,\"subscribers_count\":0}");
    fixtures.put("contributors:1", "[{\"login\":\"a\",\"id\":1},{\"login\":\"b\",\"id\":2}]");
    fixtures.put("contributor-count", "{\"total\":2}");
    var snapshot = local.collect(context, job(TaskType.STATS));
    assertThat(snapshot.contributorLogins()).containsExactly("a", "b");
  }

  @Test
  void shortDiscoveryPageCompletesWithoutIncomplete() {
    fixtures.put("accessible-repositories:1", "[{\"id\":1,\"owner\":{\"login\":\"owner\"}}]");
    var snapshot = collector.collect(context, job(TaskType.DISCOVER_REPOSITORIES));
    assertThat(json.readTree(snapshot.metadata().get("repositories"))).hasSize(1);
    assertThat(fetchedKeys).containsExactly("accessible-repositories:1");
  }

  @Test
  void fullDiscoveryPageContinuesWithinPageBudget() {
    StringBuilder page1 = new StringBuilder("[");
    for (int i = 0; i < 100; i++) {
      if (i > 0) page1.append(',');
      page1
          .append("{\"id\":")
          .append(i)
          .append(",\"owner\":{\"login\":\"")
          .append(i == 0 ? "owner" : "other")
          .append("\"}}");
    }
    page1.append(']');
    fixtures.put("accessible-repositories:1", page1.toString());
    fixtures.put("accessible-repositories:2", "[{\"id\":200,\"owner\":{\"login\":\"owner\"}}]");
    var snapshot = collector.collect(context, job(TaskType.DISCOVER_REPOSITORIES));
    assertThat(json.readTree(snapshot.metadata().get("repositories"))).hasSize(2);
    assertThat(fetchedKeys)
        .containsExactly("accessible-repositories:1", "accessible-repositories:2");
  }

  @Test
  void exhaustedDiscoveryBudgetWithFullLastPageIsIncomplete() {
    var local = collectorWithMaxPages(1);
    StringBuilder page = new StringBuilder("[");
    for (int i = 0; i < 100; i++) {
      if (i > 0) page.append(',');
      page.append("{\"id\":").append(i).append(",\"owner\":{\"login\":\"owner\"}}");
    }
    page.append(']');
    fixtures.put("accessible-repositories:1", page.toString());
    assertThatThrownBy(() -> local.collect(context, job(TaskType.DISCOVER_REPOSITORIES)))
        .isInstanceOf(IncompleteObservationException.class);
    assertThat(fetchedKeys).containsExactly("accessible-repositories:1");
  }

  @Test
  void exhaustedDiscoveryBudgetWithShortLastPageCompletes() {
    var local = collectorWithMaxPages(1);
    fixtures.put(
        "accessible-repositories:1",
        "[{\"id\":1,\"owner\":{\"login\":\"owner\"}},{\"id\":2,\"owner\":{\"login\":\"other\"}}]");
    var snapshot = local.collect(context, job(TaskType.DISCOVER_REPOSITORIES));
    assertThat(json.readTree(snapshot.metadata().get("repositories"))).hasSize(1);
  }

  @Test
  void discoveryCheckpointReusesFullPageWithoutRefetch() {
    var local = collectorWithMaxPages(2);
    StringBuilder page1 = new StringBuilder("[");
    for (int i = 0; i < 100; i++) {
      if (i > 0) page1.append(',');
      page1.append("{\"id\":").append(i).append(",\"owner\":{\"login\":\"owner\"}}");
    }
    page1.append(']');
    fixtures.put("accessible-repositories:1", page1.toString());
    fixtures.put("accessible-repositories:2", "[{\"id\":200,\"owner\":{\"login\":\"owner\"}}]");
    AtomicInteger page2 = new AtomicInteger();
    when(reader.fetch(
            anyString(),
            any(),
            eq("accessible-repositories:2"),
            anyString(),
            anyString(),
            any(),
            anyBoolean()))
        .thenAnswer(invocation -> {
          if (page2.getAndIncrement() == 0) throw new RuntimeException("page2 down");
          return json.readTree(fixtures.get("accessible-repositories:2"));
        });
    assertThatThrownBy(() -> local.collect(context, job(TaskType.DISCOVER_REPOSITORIES)))
        .hasMessageContaining("page2 down");
    assertThat(store.find(1, "accessible-repositories:1")).isPresent();
    var snapshot = local.collect(context, job(TaskType.DISCOVER_REPOSITORIES));
    assertThat(json.readTree(snapshot.metadata().get("repositories"))).hasSizeGreaterThan(1);
    assertThat(page2.get()).isEqualTo(2);
    assertThat(fetchedKeys.stream().filter("accessible-repositories:1"::equals).count())
        .isEqualTo(1);
  }

  @Test
  void contributorPagesAreCheckpointedSeparatelyAndResumeWithoutRefetch() {
    fixtures.put(
        "repository",
        "{\"id\":202,\"stargazers_count\":1,\"forks_count\":0,\"subscribers_count\":0}");
    StringBuilder page1 = new StringBuilder("[");
    for (int i = 0; i < 100; i++) {
      if (i > 0) page1.append(',');
      page1.append("{\"login\":\"u").append(i).append("\",\"id\":").append(i).append('}');
    }
    page1.append(']');
    fixtures.put("contributors:1", page1.toString());
    fixtures.put("contributors:2", "[{\"login\":\"z\",\"id\":200}]");
    fixtures.put("contributor-count", "{\"total\":101}");

    AtomicInteger page2 = new AtomicInteger();
    when(reader.fetch(
            anyString(),
            any(),
            eq("contributors:2"),
            anyString(),
            anyString(),
            any(),
            anyBoolean()))
        .thenAnswer(invocation -> {
          if (page2.getAndIncrement() == 0) throw new RuntimeException("page2 down");
          return json.readTree(fixtures.get("contributors:2"));
        });

    assertThatThrownBy(() -> collector.collect(context, job(TaskType.STATS)))
        .hasMessageContaining("page2 down");
    assertThat(store.find(1, "contributors:1")).isPresent();
    assertThat(store.find(1, "contributors:2")).isEmpty();

    var snapshot = collector.collect(context, job(TaskType.STATS));
    assertThat(snapshot.contributorLogins()).contains("u0", "z");
    assertThat(page2.get()).isEqualTo(2);
    assertThat(fetchedKeys.stream().filter("contributors:1"::equals).count()).isEqualTo(1);
  }
}
