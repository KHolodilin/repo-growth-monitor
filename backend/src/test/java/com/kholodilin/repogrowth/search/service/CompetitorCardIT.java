package com.kholodilin.repogrowth.search.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kholodilin.repogrowth.catalog.controller.HealthCheckItem;
import com.kholodilin.repogrowth.catalog.model.GitHubOwner;
import com.kholodilin.repogrowth.catalog.model.OwnerType;
import com.kholodilin.repogrowth.catalog.model.Repository;
import com.kholodilin.repogrowth.catalog.repository.GitHubOwnerJdbcRepository;
import com.kholodilin.repogrowth.catalog.repository.RepositoryJdbcRepository;
import com.kholodilin.repogrowth.common.api.ApiException;
import com.kholodilin.repogrowth.common.persistence.SqlTime;
import com.kholodilin.repogrowth.search.model.CompetitorProfile;
import com.kholodilin.repogrowth.search.model.SearchQuery;
import com.kholodilin.repogrowth.search.repository.CompetitorProfileJdbcRepository;
import com.kholodilin.repogrowth.support.AbstractPostgresTest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class CompetitorCardIT extends AbstractPostgresTest {

  @Autowired CompetitorCardService competitorCardService;
  @Autowired SearchQueryService searchQueryService;
  @Autowired GitHubOwnerJdbcRepository ownerJdbcRepository;
  @Autowired RepositoryJdbcRepository repositoryJdbcRepository;
  @Autowired JdbcClient jdbcClient;
  @Autowired CompetitorProfileJdbcRepository profileRepository;

  private Repository kafka;
  private SearchQuery query;
  private LocalDate older;
  private LocalDate newer;

  @BeforeEach
  void seed() {
    wipeRepositoryData(jdbcClient);
    GitHubOwner owner =
        ownerJdbcRepository.upsert(100L, "acme", OwnerType.USER, null, "https://github.com/acme");
    kafka =
        repositoryJdbcRepository.upsertKeepingTracking(
            new Repository(
                null,
                301L,
                owner.id(),
                "kafka-starter",
                "acme/kafka-starter",
                "kafka",
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
  void usesTheStoredProfileAndLeavesMissingDaysEmpty() {
    profileRepository.save(
        new CompetitorProfile(
            500L,
            "other/repo",
            "A production-ready neighbor for the transactional outbox pattern.",
            "https://github.com/other/repo",
            List.of("kafka", "outbox", "spring"),
            "https://other.example",
            false,
            Instant.parse("2026-09-01T00:00:00Z"),
            Instant.parse("2026-06-01T00:00:00Z"),
            true,
            true,
            true,
            true,
            true,
            Instant.parse("2026-09-20T00:00:00Z")));

    CompetitorCardService.CompetitorCard first = competitorCardService.card(query.id(), 500L);
    CompetitorCardService.CompetitorCard second = competitorCardService.card(query.id(), 500L);

    assertThat(first.fullName()).isEqualTo("other/repo");
    assertThat(first.description()).contains("production-ready");
    assertThat(first.htmlUrl()).isEqualTo("https://github.com/other/repo");
    assertThat(first.topics()).containsExactly("kafka", "outbox", "spring");
    assertThat(first.discoverability())
        .extracting(HealthCheckItem::label)
        .doesNotContain("Search Queries configured");
    assertThat(first.discoverability()).hasSize(12);
    assertThat(first.discoverability())
        .filteredOn(item -> item.label().equals("README has H1"))
        .allMatch(HealthCheckItem::passed);
    assertThat(first.points())
        .extracting(CompetitorCardService.CompetitorPoint::date)
        .containsExactly(older, newer);
    assertThat(first.points().get(0).stars()).isEqualTo(10);
    assertThat(first.points().get(0).forks()).isEqualTo(1);
    assertThat(first.points().get(1).stars()).isNull();
    assertThat(first.points().get(1).forks()).isNull();
    assertThat(second).isEqualTo(first);
  }

  @Test
  void buildsAnEmptyCardWithoutTouchingGitHubWhenNoProfileIsStored() {
    CompetitorCardService.CompetitorCard card = competitorCardService.card(query.id(), 501L);
    competitorCardService.card(query.id(), 501L);

    assertThat(card.fullName()).isEqualTo("bad/name");
    assertThat(card.description()).isNull();
    assertThat(card.topics()).isEmpty();
    assertThat(card.htmlUrl()).isNull();
    assertThat(card.discoverability())
        .filteredOn(item -> item.label().equals("README exists"))
        .noneMatch(HealthCheckItem::passed);
    assertThat(profileRepository.find(501L)).isEmpty();
    assertThat(competitorCardService.card(query.id(), 502L).fullName()).isEqualTo("noslash");
  }

  @Test
  void rejectsAnUnknownSearchOrARepositoryThatWasNeverRanked() {
    assertThatThrownBy(() -> competitorCardService.card(999_999L, 500L))
        .isInstanceOf(ApiException.class);
    assertThatThrownBy(() -> competitorCardService.card(query.id(), 404L))
        .isInstanceOf(ApiException.class);
  }

  private void storeSnapshot(LocalDate businessDate) {
    Instant collectedAt =
        businessDate.atTime(9, 30).toInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
    jdbcClient
        .sql(
            """
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
    return jdbcClient
        .sql(
            """
            SELECT id FROM search_run
            WHERE search_query_id = :searchQueryId AND business_date = :businessDate
            """)
        .param("searchQueryId", query.id())
        .param("businessDate", businessDate)
        .query(Long.class)
        .single();
  }

  private void insertResult(
      long searchRunId,
      int position,
      long githubRepositoryId,
      String fullName,
      int stars,
      int forks) {
    String owner = fullName.contains("/") ? fullName.substring(0, fullName.indexOf('/')) : fullName;
    jdbcClient
        .sql(
            """
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
