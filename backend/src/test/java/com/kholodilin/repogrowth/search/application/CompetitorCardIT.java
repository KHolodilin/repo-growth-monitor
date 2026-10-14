package com.kholodilin.repogrowth.search.application;

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
import com.kholodilin.repogrowth.search.domain.SearchQuery;
import com.kholodilin.repogrowth.support.AbstractPostgresTest;
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

class CompetitorCardIT extends AbstractPostgresTest {

    @Autowired
    CompetitorCardService competitorCardService;
    @Autowired
    SearchQueryService searchQueryService;
    @Autowired
    GitHubOwnerJdbcRepository ownerJdbcRepository;
    @Autowired
    RepositoryJdbcRepository repositoryJdbcRepository;
    @Autowired
    JdbcClient jdbcClient;
    @MockitoBean
    GitHubClient gitHubClient;

    private Repository kafka;
    private SearchQuery query;
    private LocalDate older;
    private LocalDate newer;

    @BeforeEach
    void seed() {
        wipeRepositoryData(jdbcClient);
        GitHubOwner owner = ownerJdbcRepository.upsert(100L, "acme", OwnerType.USER, null, "https://github.com/acme");
        kafka = repositoryJdbcRepository.upsertKeepingTracking(new Repository(
                null, 301L, owner.id(), "kafka-starter", "acme/kafka-starter", "kafka", "PUBLIC", "main", "Java",
                false, false, 1, 0, 0, 0, 0, false,
                Instant.parse("2024-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"),
                null, null, null, null, null, null
        ));
        query = searchQueryService.create(kafka.id(), "outbox", "transactional outbox", true, 50);
        newer = LocalDate.of(2026, 9, 23);
        older = newer.minusDays(1);
        storeSnapshot(older);
        storeSnapshot(newer);
        insertResult(runId(older), 1, 500L, "other/repo", 10, 1);
        insertResult(runId(newer), 2, 301L, "acme/kafka-starter", 21, 4);
        insertResult(runId(older), 3, 501L, "bad/name", 1, 0);
        insertResult(runId(older), 4, 502L, "noslash", 1, 0);
    }

    @Test
    void loadsThePublicProfileOnceAndLeavesMissingDaysEmpty() {
        when(gitHubClient.getRepository("other", "repo")).thenReturn(remote(false, "A production-ready neighbor for the transactional outbox pattern."));
        when(gitHubClient.getReadme("other", "repo")).thenReturn(Optional.of("# other\n\nother is a neighbor."));
        when(gitHubClient.getCommunityProfile("other", "repo")).thenReturn(GitHubCommunityProfileResponse.empty());
        when(gitHubClient.getSocialPreview("other", "repo")).thenReturn(new GitHubSocialPreview(true, "https://example.test/og.png"));
        when(gitHubClient.latestReleaseAt("other", "repo")).thenReturn(Optional.of(Instant.parse("2026-06-01T00:00:00Z")));

        CompetitorCardService.CompetitorCard first = competitorCardService.card(query.id(), 500L);
        CompetitorCardService.CompetitorCard second = competitorCardService.card(query.id(), 500L);

        assertThat(first.fullName()).isEqualTo("other/repo");
        assertThat(first.description()).contains("production-ready");
        assertThat(first.htmlUrl()).isEqualTo("https://github.com/other/repo");
        assertThat(first.topics()).containsExactly("kafka", "outbox", "spring");
        assertThat(first.discoverability()).extracting(HealthCheckItem::label).doesNotContain("Search Queries configured");
        assertThat(first.discoverability()).hasSize(12);
        assertThat(first.discoverability()).filteredOn(item -> item.label().equals("README has H1")).allMatch(HealthCheckItem::passed);
        assertThat(first.points()).extracting(CompetitorCardService.CompetitorPoint::date).containsExactly(older, newer);
        assertThat(first.points().get(0).stars()).isEqualTo(10);
        assertThat(first.points().get(0).forks()).isEqualTo(1);
        assertThat(first.points().get(1).stars()).isNull();
        assertThat(first.points().get(1).forks()).isNull();
        assertThat(second.topics()).containsExactly("kafka", "outbox", "spring");
        verify(gitHubClient, times(1)).getRepository("other", "repo");
    }

    @Test
    void keepsACardWhenOptionalGitHubReadsFailAndDoesNotCacheAMissingRepository() {
        when(gitHubClient.getRepository("bad", "name")).thenReturn(remote(true, " "));
        when(gitHubClient.getReadme("bad", "name")).thenThrow(new RuntimeException("readme"));
        when(gitHubClient.getCommunityProfile("bad", "name")).thenThrow(new RuntimeException("community"));
        when(gitHubClient.getSocialPreview("bad", "name")).thenThrow(new RuntimeException("preview"));
        when(gitHubClient.latestReleaseAt("bad", "name")).thenThrow(new RuntimeException("release"));

        CompetitorCardService.CompetitorCard card = competitorCardService.card(query.id(), 501L);
        competitorCardService.card(query.id(), 501L);

        assertThat(card.description()).isNull();
        assertThat(card.topics()).isEmpty();
        assertThat(card.htmlUrl()).isEqualTo("https://github.com/bad/name");
        assertThat(card.discoverability()).filteredOn(item -> item.label().equals("README exists")).noneMatch(HealthCheckItem::passed);
        assertThat(card.discoverability()).filteredOn(item -> item.label().equals("Repository not archived")).noneMatch(HealthCheckItem::passed);
        verify(gitHubClient, times(1)).getRepository("bad", "name");

        when(gitHubClient.getRepository("other", "repo")).thenThrow(new RuntimeException("missing"));
        assertThatThrownBy(() -> competitorCardService.card(query.id(), 500L)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> competitorCardService.card(query.id(), 500L)).isInstanceOf(RuntimeException.class);
        verify(gitHubClient, times(2)).getRepository("other", "repo");
    }

    @Test
    void rejectsANameThatCannotBeOpenedOnGitHub() {
        assertThatThrownBy(() -> competitorCardService.card(query.id(), 502L)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> competitorCardService.card(999_999L, 500L)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> competitorCardService.card(query.id(), 404L)).isInstanceOf(ApiException.class);
        assertThat(CompetitorCardService.ownerAndName("other/repo")).containsExactly("other", "repo");
        assertThatThrownBy(() -> CompetitorCardService.ownerAndName(null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> CompetitorCardService.ownerAndName("trailing/")).isInstanceOf(ApiException.class);
    }

    private GitHubRepositoryResponse remote(boolean archived, String description) {
        return new GitHubRepositoryResponse(
                500L,
                archived ? "name" : "repo",
                archived ? " " : "other/repo",
                description,
                false,
                "public",
                "main",
                "Java",
                false,
                archived,
                10,
                1,
                2,
                0,
                archived ? " " : "https://github.com/other/repo",
                Instant.parse("2024-01-01T00:00:00Z"),
                Instant.parse("2026-09-01T00:00:00Z"),
                Instant.now(),
                null,
                archived ? List.of() : List.of("kafka", "outbox", "spring"),
                archived ? " " : "https://other.example",
                archived ? null : new GitHubLicenseResponse("mit", "MIT", "MIT")
        );
    }

    private void storeSnapshot(LocalDate businessDate) {
        Instant collectedAt = businessDate.atTime(9, 30).toInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        jdbcClient.sql("""
                        INSERT INTO search_run (
                            search_query_id, repository_id, business_date, status,
                            started_at, completed_at, snapshot_at, total_count, tracked_repository_position
                        )
                        VALUES (
                            :searchQueryId, :repositoryId, :businessDate, 'SUCCESS',
                            :collectedAt, :collectedAt, :collectedAt, 50, 2
                        )
                        """)
                .param("searchQueryId", query.id())
                .param("repositoryId", kafka.id())
                .param("businessDate", businessDate)
                .param("collectedAt", SqlTime.ts(collectedAt))
                .update();
    }

    private long runId(LocalDate businessDate) {
        return jdbcClient.sql("""
                        SELECT id FROM search_run
                        WHERE search_query_id = :searchQueryId AND business_date = :businessDate
                        """)
                .param("searchQueryId", query.id())
                .param("businessDate", businessDate)
                .query(Long.class)
                .single();
    }

    private void insertResult(long searchRunId, int position, long githubRepositoryId, String fullName, int stars, int forks) {
        String owner = fullName.contains("/") ? fullName.substring(0, fullName.indexOf('/')) : fullName;
        jdbcClient.sql("""
                        INSERT INTO search_result (
                            search_run_id, position, github_repository_id, full_name, owner, stars, forks
                        ) VALUES (
                            :searchRunId, :position, :githubRepositoryId, :fullName, :owner, :stars, :forks
                        )
                        """)
                .param("searchRunId", searchRunId)
                .param("position", position)
                .param("githubRepositoryId", githubRepositoryId)
                .param("fullName", fullName)
                .param("owner", owner)
                .param("stars", stars)
                .param("forks", forks)
                .update();
    }
}
