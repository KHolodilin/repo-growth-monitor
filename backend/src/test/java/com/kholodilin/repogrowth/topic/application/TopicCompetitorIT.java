package com.kholodilin.repogrowth.topic.application;

import com.kholodilin.repogrowth.common.api.ApiException;
import com.kholodilin.repogrowth.common.persistence.SqlTime;
import com.kholodilin.repogrowth.github.client.GitHubClient;
import com.kholodilin.repogrowth.github.model.GitHubCommunityProfileResponse;
import com.kholodilin.repogrowth.github.model.GitHubLicenseResponse;
import com.kholodilin.repogrowth.github.model.GitHubRepositoryResponse;
import com.kholodilin.repogrowth.github.model.GitHubSocialPreview;
import com.kholodilin.repogrowth.repository.api.HealthCheckItem;
import com.kholodilin.repogrowth.repository.domain.GitHubOwner;
import com.kholodilin.repogrowth.repository.domain.OwnerType;
import com.kholodilin.repogrowth.repository.domain.Repository;
import com.kholodilin.repogrowth.repository.persistence.GitHubOwnerJdbcRepository;
import com.kholodilin.repogrowth.repository.persistence.RepositoryJdbcRepository;
import com.kholodilin.repogrowth.search.application.CompetitorCardService;
import com.kholodilin.repogrowth.support.AbstractPostgresTest;
import com.kholodilin.repogrowth.topic.domain.TopicWatch;
import com.kholodilin.repogrowth.topic.persistence.TopicWatchJdbcRepository;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TopicCompetitorIT extends AbstractPostgresTest {

    @MockitoBean
    GitHubClient gitHubClient;

    @Autowired
    TopicCompetitorService topicCompetitorService;
    @Autowired
    TopicWatchSync topicWatchSync;
    @Autowired
    TopicWatchJdbcRepository watchRepository;
    @Autowired
    GitHubOwnerJdbcRepository ownerJdbcRepository;
    @Autowired
    RepositoryJdbcRepository repositoryJdbcRepository;
    @Autowired
    JdbcClient jdbcClient;

    private Repository repository;
    private TopicWatch allKafka;
    private TopicWatch languageKafka;
    private LocalDate older;
    private LocalDate newer;
    private LocalDate missed;

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
        repositoryJdbcRepository.replaceTopics(repository.id(), List.of("kafka"));
        topicWatchSync.sync(repository.id());
        newer = LocalDate.of(2026, 9, 23);
        older = newer.minusDays(2);
        missed = newer.minusDays(1);
        allKafka = watch(true);
        languageKafka = watch(false);
        storeSnapshot(allKafka.id(), older);
        storeSnapshot(allKafka.id(), newer);
        storeMissed(allKafka.id(), missed);
        insertResult(runId(allKafka.id(), older), 1, 500L, "other/repo", 10, 1);
        insertResult(runId(allKafka.id(), older), 2, 301L, "acme/kafka-starter", 21, 4);
        insertResult(runId(allKafka.id(), newer), 1, 301L, "acme/kafka-starter", 22, 5);
        storeSnapshot(languageKafka.id(), newer);
        insertResult(runId(languageKafka.id(), newer), 1, 700L, "lang/only", 8, 2);
    }

    @Test
    void usesTheCachedProfileAndBreaksTheLineOnACollectedDayWithoutThisRepository() {
        when(gitHubClient.getRepository("other", "repo")).thenReturn(remote("other/repo", "A production-ready neighbor."));
        when(gitHubClient.getReadme("other", "repo")).thenReturn(Optional.of("# other\n\nother is a neighbor."));
        when(gitHubClient.getCommunityProfile("other", "repo")).thenReturn(GitHubCommunityProfileResponse.empty());
        when(gitHubClient.getSocialPreview("other", "repo")).thenReturn(new GitHubSocialPreview(true, "https://example.test/og.png"));
        when(gitHubClient.latestReleaseAt("other", "repo")).thenReturn(Optional.of(Instant.parse("2026-06-01T00:00:00Z")));

        CompetitorCardService.CompetitorCard first = topicCompetitorService.card(repository.id(), "kafka", "all", 500L);
        CompetitorCardService.CompetitorCard second = topicCompetitorService.card(repository.id(), "kafka", "all", 500L);

        assertThat(first.fullName()).isEqualTo("other/repo");
        assertThat(first.description()).contains("production-ready");
        assertThat(first.htmlUrl()).isEqualTo("https://github.com/other/repo");
        assertThat(first.topics()).containsExactly("kafka", "outbox", "spring");
        assertThat(first.discoverability()).extracting(HealthCheckItem::label).doesNotContain("Search Queries configured");
        assertThat(first.discoverability()).hasSize(12);
        assertThat(first.points()).extracting(CompetitorCardService.CompetitorPoint::date).containsExactly(older, newer);
        assertThat(first.points().get(0).stars()).isEqualTo(10);
        assertThat(first.points().get(0).forks()).isEqualTo(1);
        assertThat(first.points().get(1).stars()).isNull();
        assertThat(first.points().get(1).forks()).isNull();
        assertThat(second.topics()).containsExactly("kafka", "outbox", "spring");
        verify(gitHubClient, times(1)).getRepository("other", "repo");
    }

    @Test
    void keepsLanguageResultsSeparateAndRejectsARepositoryThatNeverAppeared() {
        when(gitHubClient.getRepository("lang", "only")).thenReturn(remote("lang/only", "Language scoped neighbor."));
        when(gitHubClient.getReadme("lang", "only")).thenReturn(Optional.empty());
        when(gitHubClient.getCommunityProfile("lang", "only")).thenReturn(GitHubCommunityProfileResponse.empty());
        when(gitHubClient.getSocialPreview("lang", "only")).thenReturn(new GitHubSocialPreview(false, null));
        when(gitHubClient.latestReleaseAt("lang", "only")).thenReturn(Optional.empty());

        CompetitorCardService.CompetitorCard card = topicCompetitorService.card(repository.id(), "kafka", "language", 700L);

        assertThat(card.fullName()).isEqualTo("lang/only");
        assertThat(card.points()).extracting(CompetitorCardService.CompetitorPoint::date).containsExactly(newer);
        assertThat(card.points().getFirst().stars()).isEqualTo(8);
        assertThatThrownBy(() -> topicCompetitorService.card(repository.id(), "kafka", "all", 700L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not found");
        assertThatThrownBy(() -> topicCompetitorService.card(repository.id(), "missing", "all", 500L))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> topicCompetitorService.card(repository.id(), "kafka", "all", 404L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not found");
    }

    private TopicWatch watch(boolean allLanguages) {
        return watchRepository.findEnabledByRepository(repository.id(), allLanguages).stream()
                .filter(item -> "kafka".equals(item.topic()))
                .findFirst()
                .orElseThrow();
    }

    private GitHubRepositoryResponse remote(String fullName, String description) {
        String name = fullName.substring(fullName.indexOf('/') + 1);
        return new GitHubRepositoryResponse(
                500L,
                name,
                fullName,
                description,
                false,
                "public",
                "main",
                "Java",
                false,
                false,
                10,
                1,
                2,
                0,
                "https://github.com/" + fullName,
                Instant.parse("2024-01-01T00:00:00Z"),
                Instant.parse("2026-09-01T00:00:00Z"),
                Instant.now(),
                null,
                List.of("kafka", "outbox", "spring"),
                "https://other.example",
                new GitHubLicenseResponse("mit", "MIT", "MIT")
        );
    }

    private void storeSnapshot(long topicWatchId, LocalDate businessDate) {
        Instant collectedAt = businessDate.atTime(9, 30).toInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        jdbcClient.sql("""
                        INSERT INTO topic_run (
                            topic_watch_id, repository_id, business_date, status,
                            started_at, completed_at, snapshot_at, total_count, tracked_repository_position
                        )
                        VALUES (
                            :topicWatchId, :repositoryId, :businessDate, 'SUCCESS',
                            :collectedAt, :collectedAt, :collectedAt, 50, 2
                        )
                        """)
                .param("topicWatchId", topicWatchId)
                .param("repositoryId", repository.id())
                .param("businessDate", businessDate)
                .param("collectedAt", SqlTime.ts(collectedAt))
                .update();
    }

    private void storeMissed(long topicWatchId, LocalDate businessDate) {
        jdbcClient.sql("""
                        INSERT INTO topic_run (topic_watch_id, repository_id, business_date, status)
                        VALUES (:topicWatchId, :repositoryId, :businessDate, 'MISSED')
                        """)
                .param("topicWatchId", topicWatchId)
                .param("repositoryId", repository.id())
                .param("businessDate", businessDate)
                .update();
    }

    private long runId(long topicWatchId, LocalDate businessDate) {
        return jdbcClient.sql("""
                        SELECT id FROM topic_run
                        WHERE topic_watch_id = :topicWatchId AND business_date = :businessDate
                        """)
                .param("topicWatchId", topicWatchId)
                .param("businessDate", businessDate)
                .query(Long.class)
                .single();
    }

    private void insertResult(long topicRunId, int position, long githubRepositoryId, String fullName, int stars, int forks) {
        String owner = fullName.substring(0, fullName.indexOf('/'));
        jdbcClient.sql("""
                        INSERT INTO topic_result (
                            topic_run_id, position, github_repository_id, full_name, owner,
                            stars, watchers, forks, html_url
                        ) VALUES (
                            :topicRunId, :position, :githubRepositoryId, :fullName, :owner,
                            :stars, 0, :forks, :htmlUrl
                        )
                        """)
                .param("topicRunId", topicRunId)
                .param("position", position)
                .param("githubRepositoryId", githubRepositoryId)
                .param("fullName", fullName)
                .param("owner", owner)
                .param("stars", stars)
                .param("forks", forks)
                .param("htmlUrl", "https://github.com/" + fullName)
                .update();
    }
}
