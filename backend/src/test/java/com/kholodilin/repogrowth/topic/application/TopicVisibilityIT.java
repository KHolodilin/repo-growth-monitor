package com.kholodilin.repogrowth.topic.application;

import com.kholodilin.repogrowth.collection.planner.PlanningWindow;
import com.kholodilin.repogrowth.common.persistence.SqlTime;
import com.kholodilin.repogrowth.github.client.GitHubClient;
import com.kholodilin.repogrowth.github.model.GitHubOwnerResponse;
import com.kholodilin.repogrowth.github.model.GitHubSearchItem;
import com.kholodilin.repogrowth.github.model.GitHubSearchResponse;
import com.kholodilin.repogrowth.repository.domain.GitHubOwner;
import com.kholodilin.repogrowth.repository.domain.OwnerType;
import com.kholodilin.repogrowth.repository.domain.Repository;
import com.kholodilin.repogrowth.repository.persistence.GitHubOwnerJdbcRepository;
import com.kholodilin.repogrowth.repository.persistence.RepositoryJdbcRepository;
import com.kholodilin.repogrowth.search.application.RankChangeMath;
import com.kholodilin.repogrowth.support.AbstractPostgresTest;
import com.kholodilin.repogrowth.topic.domain.TopicWatch;
import com.kholodilin.repogrowth.topic.persistence.TopicWatchJdbcRepository;
import com.kholodilin.repogrowth.topic.worker.TopicWorker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class TopicVisibilityIT extends AbstractPostgresTest {

    @MockitoBean
    GitHubClient gitHubClient;

    @Autowired
    TopicVisibilityService topicVisibilityService;
    @Autowired
    TopicWatchSync topicWatchSync;
    @Autowired
    TopicWatchJdbcRepository watchRepository;
    @Autowired
    TopicWorker topicWorker;
    @Autowired
    GitHubOwnerJdbcRepository ownerJdbcRepository;
    @Autowired
    RepositoryJdbcRepository repositoryJdbcRepository;
    @Autowired
    PlanningWindow planningWindow;
    @Autowired
    JdbcClient jdbcClient;

    private Repository repository;

    @BeforeEach
    void seed() {
        wipeRepositoryData(jdbcClient);
        GitHubOwner owner = ownerJdbcRepository.upsert(100L, "acme", OwnerType.USER, null, "https://github.com/acme");
        repository = repositoryJdbcRepository.upsertKeepingTracking(new Repository(
                null, 301L, owner.id(), "kafka-starter", "acme/kafka-starter", "demo", "PUBLIC", "main", "Java",
                false, false, 1, 0, 0, 0, 0, false,
                Instant.parse("2024-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"),
                null, null, null, null, null, null
        ));
        repositoryJdbcRepository.markAccountAccessible(repository.id());
        repositoryJdbcRepository.setTracking(repository.id(), true);
        repository = repositoryJdbcRepository.findById(repository.id()).orElseThrow();
        repositoryJdbcRepository.replaceTopics(repository.id(), List.of("spring-boot", "kafka"));
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
        assertThat(after.stream().filter(watch -> "spring-boot".equals(watch.topic()) && watch.enabled())).isEmpty();
        assertThat(after.stream().filter(watch -> "kafka".equals(watch.topic()) && watch.enabled())).hasSize(2);
    }

    @Test
    void visibilitySplitsAllAndLanguageScopes() {
        List<TopicVisibilityService.TopicHistory> all = topicVisibilityService.visibility(repository.id(), "all");
        List<TopicVisibilityService.TopicHistory> language = topicVisibilityService.visibility(repository.id(), "language");
        assertThat(all).extracting(item -> item.watch().topic()).containsExactly("kafka", "spring-boot");
        assertThat(all).allMatch(item -> item.watch().allLanguages());
        assertThat(language).allMatch(item -> "Java".equals(item.watch().language()));
        assertThat(language).extracting(item -> item.watch().searchQuery())
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
    void workerRecordsPositionFromSortedTopicSearch() {
        when(gitHubClient.searchRepositories(eq("topic:kafka"), anyInt(), eq("stars"), eq("desc")))
                .thenReturn(new GitHubSearchResponse(12, List.of(
                        item(999L, "other/lib"),
                        item(301L, "acme/kafka-starter")
                )));
        topicWatchSync.sync(repository.id());
        TopicWatch watch = watchRepository.findEnabledByRepository(repository.id(), true).stream()
                .filter(item -> "kafka".equals(item.topic()))
                .findFirst()
                .orElseThrow();
        topicVisibilityService.runNow(watch.id());
        assertThat(topicWorker.poll("topic-it")).isTrue();

        TopicVisibilityService.TopicHistory history = topicVisibilityService.history(watch.id());
        assertThat(history.currentRank()).isEqualTo(2);
        TopicVisibilityService.TopicRunResults results = topicVisibilityService.latestResults(watch.id());
        assertThat(results.rows()).hasSize(2);
        assertThat(results.rows().get(1).result().githubRepositoryId()).isEqualTo(301L);
    }

    @Test
    void searchVisibilityQueriesStayUntouched() {
        topicVisibilityService.visibility(repository.id(), "all");
        assertThat(jdbcClient.sql("SELECT COUNT(*) FROM search_query").query(Integer.class).single()).isZero();
    }

    private void storeSnapshot(long topicWatchId, LocalDate businessDate, Integer position, int totalCount) {
        Instant collectedAt = businessDate.atTime(9, 30).toInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        jdbcClient.sql("""
                        INSERT INTO topic_run (
                            topic_watch_id, repository_id, business_date, status,
                            started_at, completed_at, snapshot_at, total_count, tracked_repository_position
                        )
                        VALUES (
                            :topicWatchId, :repositoryId, :businessDate, 'SUCCESS',
                            :collectedAt, :collectedAt, :collectedAt, :totalCount, :position
                        )
                        """)
                .param("topicWatchId", topicWatchId)
                .param("repositoryId", repository.id())
                .param("businessDate", businessDate)
                .param("collectedAt", SqlTime.ts(collectedAt))
                .param("totalCount", totalCount)
                .param("position", position)
                .update();
    }

    private static GitHubSearchItem item(long githubId, String fullName) {
        return new GitHubSearchItem(
                githubId,
                fullName,
                new GitHubOwnerResponse(1L, "acme", "User", null, null),
                10,
                1,
                "Java",
                "demo",
                "https://github.com/" + fullName,
                Instant.parse("2024-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z")
        );
    }
}
