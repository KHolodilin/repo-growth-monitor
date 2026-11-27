package com.kholodilin.repogrowth.topics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kholodilin.repogrowth.catalog.model.GitHubOwner;
import com.kholodilin.repogrowth.catalog.model.OwnerType;
import com.kholodilin.repogrowth.catalog.model.Repository;
import com.kholodilin.repogrowth.catalog.repository.GitHubOwnerJdbcRepository;
import com.kholodilin.repogrowth.catalog.repository.RepositoryJdbcRepository;
import com.kholodilin.repogrowth.collection.planner.PlanningWindow;
import com.kholodilin.repogrowth.common.api.ApiException;
import com.kholodilin.repogrowth.common.persistence.SqlTime;
import com.kholodilin.repogrowth.search.service.RankChangeMath;
import com.kholodilin.repogrowth.support.AbstractPostgresTest;
import com.kholodilin.repogrowth.topics.model.TopicWatch;
import com.kholodilin.repogrowth.topics.repository.TopicWatchJdbcRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class TopicVisibilityIT extends AbstractPostgresTest {

  @Autowired TopicVisibilityService topicVisibilityService;
  @Autowired TopicWatchSync topicWatchSync;
  @Autowired TopicWatchJdbcRepository watchRepository;
  @Autowired GitHubOwnerJdbcRepository ownerJdbcRepository;
  @Autowired RepositoryJdbcRepository repositoryJdbcRepository;
  @Autowired PlanningWindow planningWindow;
  @Autowired JdbcClient jdbcClient;

  private Repository repository;

  @BeforeEach
  void seed() {
    wipeRepositoryData(jdbcClient);
    GitHubOwner owner =
        ownerJdbcRepository.upsert(100L, "acme", OwnerType.USER, null, "https://github.com/acme");
    repository =
        repositoryJdbcRepository.upsertKeepingTracking(
            new Repository(
                null,
                301L,
                owner.id(),
                "kafka-starter",
                "acme/kafka-starter",
                "demo",
                "PUBLIC",
                "main",
                "Java",
                false,
                false,
                1,
                0,
                0,
                0,
                0,
                false,
                Instant.parse("2024-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z"),
                null,
                null,
                null,
                null,
                null,
                null));
    repositoryJdbcRepository.markAccountAccessible(repository.id());
    repositoryJdbcRepository.setTracking(repository.id(), true);
    repository = repositoryJdbcRepository.findById(repository.id()).orElseThrow();
    repositoryJdbcRepository.replaceTopics(repository.id(), List.of("spring-boot", "kafka"));
    topicWatchSync.sync(repository.id());
  }

  @Test
  void syncCreatesAllAndLanguageWatchesAndDisablesRemovedTopics() {
    topicWatchSync.sync(repository.id());
    List<TopicWatch> watches = watchRepository.findByRepository(repository.id());
    assertThat(watches).hasSize(4);
    assertThat(watches).extracting(TopicWatch::topic).containsOnly("spring-boot", "kafka");
    assertThat(watches.stream().filter(TopicWatch::allLanguages)).hasSize(2);
    assertThat(watches.stream().filter(watch -> "Java".equals(watch.language()))).hasSize(2);

    repositoryJdbcRepository.replaceTopics(repository.id(), List.of("kafka"));
    topicWatchSync.sync(repository.id());
    List<TopicWatch> after = watchRepository.findByRepository(repository.id());
    assertThat(
            after.stream().filter(watch -> "spring-boot".equals(watch.topic()) && watch.enabled()))
        .isEmpty();
    assertThat(after.stream().filter(watch -> "kafka".equals(watch.topic()) && watch.enabled()))
        .hasSize(2);
  }

  @Test
  void visibilitySplitsAllAndLanguageScopes() {
    List<TopicVisibilityService.TopicHistory> all =
        topicVisibilityService.visibility(repository.id(), "all");
    List<TopicVisibilityService.TopicHistory> language =
        topicVisibilityService.visibility(repository.id(), "language");
    assertThat(all)
        .extracting(item -> item.watch().topic())
        .containsExactly("kafka", "spring-boot");
    assertThat(all).allMatch(item -> item.watch().allLanguages());
    assertThat(language).allMatch(item -> "Java".equals(item.watch().language()));
    assertThat(language)
        .extracting(item -> item.watch().searchQuery())
        .containsExactly("topic:kafka language:Java", "topic:spring-boot language:Java");
  }

  @Test
  void historyTracksRankAndExited() {
    topicWatchSync.sync(repository.id());
    TopicWatch watch = watchRepository.findEnabledByRepository(repository.id(), true).getFirst();
    LocalDate today = planningWindow.businessDate();
    storeSnapshot(watch.id(), today.minusDays(1), 4, 80);
    storeSnapshot(watch.id(), today, null, 80);

    TopicVisibilityService.TopicHistory history = topicVisibilityService.history(watch.id());
    assertThat(history.currentRank()).isNull();
    assertThat(history.change().kind()).isEqualTo(RankChangeMath.Kind.EXITED);
    assertThat(history.bestRank()).isEqualTo(4);
  }

  @Test
  void searchVisibilityQueriesStayUntouched() {
    topicVisibilityService.visibility(repository.id(), "all");
    assertThat(jdbcClient.sql("SELECT COUNT(*) FROM search_query").query(Integer.class).single())
        .isZero();
    assertThatThrownBy(() -> topicVisibilityService.runAll(repository.id(), "language"))
        .isInstanceOf(ApiException.class)
        .hasMessage("Collector orchestration is required");
    assertThatThrownBy(() -> topicVisibilityService.runNow(1L))
        .isInstanceOf(ApiException.class)
        .hasMessage("Collector orchestration is required");
    assertThat(
            topicVisibilityService.historyByTopic(repository.id(), "kafka", "all").watch().topic())
        .isEqualTo("kafka");
    assertThatThrownBy(() -> topicVisibilityService.visibility(repository.id(), "nope"))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(
            () -> topicVisibilityService.historyByTopic(repository.id(), "missing", "all"))
        .isInstanceOf(ApiException.class);
  }

  @Test
  void latestResultsExposeDeltasAcrossSnapshots() {
    TopicWatch watch = watchRepository.findEnabledByRepository(repository.id(), true).getFirst();
    LocalDate today = planningWindow.businessDate();
    long previous = storeSnapshot(watch.id(), today.minusDays(1), 4, 80);
    long current = storeSnapshot(watch.id(), today, 2, 80);
    insertResult(previous, 301L, 4);
    insertResult(previous, 999L, 1);
    insertResult(current, 301L, 2);
    insertResult(current, 999L, 1);

    var latest = topicVisibilityService.latestResults(watch.id());
    assertThat(latest.rows()).hasSize(2);
    assertThat(
            latest.rows().stream()
                .filter(row -> row.result().githubRepositoryId() == 301L)
                .findFirst()
                .orElseThrow()
                .positionDelta())
        .isEqualTo(2);
    assertThat(
            topicVisibilityService
                .latestResultsByTopic(repository.id(), watch.topic(), "all")
                .run()
                .id())
        .isEqualTo(current);
    assertThat(topicVisibilityService.results(current).watch().id()).isEqualTo(watch.id());
  }

  private long storeSnapshot(
      long topicWatchId, LocalDate businessDate, Integer position, int totalCount) {
    Instant collectedAt =
        businessDate.atTime(9, 30).toInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
    return jdbcClient
        .sql(
            """
            INSERT INTO topic_run (
                topic_watch_id, repository_id, business_date, status,
                started_at, completed_at, snapshot_at, total_count, tracked_repository_position
            )
            VALUES (
                :topicWatchId, :repositoryId, :businessDate, 'SUCCESS',
                :collectedAt, :collectedAt, :collectedAt, :totalCount, :position
            )
            RETURNING id
            """)
        .param("topicWatchId", topicWatchId)
        .param("repositoryId", repository.id())
        .param("businessDate", businessDate)
        .param("collectedAt", SqlTime.ts(collectedAt))
        .param("totalCount", totalCount)
        .param("position", position)
        .query(Long.class)
        .single();
  }

  private void insertResult(long topicRunId, long githubRepositoryId, int position) {
    jdbcClient
        .sql(
            """
            INSERT INTO topic_result (
                topic_run_id, position, github_repository_id, full_name, owner,
                stars, watchers, forks, language, html_url
            ) VALUES (
                :runId, :position, :githubId, :fullName, 'acme',
                1, 0, 0, 'Java', :htmlUrl
            )
            """)
        .param("runId", topicRunId)
        .param("position", position)
        .param("githubId", githubRepositoryId)
        .param("fullName", "acme/repo-" + githubRepositoryId)
        .param("htmlUrl", "https://github.com/acme/repo-" + githubRepositoryId)
        .update();
  }
}
