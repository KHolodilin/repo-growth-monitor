package com.kholodilin.repogrowth.search.application;

import com.kholodilin.repogrowth.collection.planner.PlanningWindow;
import com.kholodilin.repogrowth.common.api.ApiException;
import com.kholodilin.repogrowth.common.persistence.SqlTime;
import com.kholodilin.repogrowth.repository.domain.GitHubOwner;
import com.kholodilin.repogrowth.repository.domain.OwnerType;
import com.kholodilin.repogrowth.repository.domain.Repository;
import com.kholodilin.repogrowth.repository.persistence.GitHubOwnerJdbcRepository;
import com.kholodilin.repogrowth.repository.persistence.RepositoryJdbcRepository;
import com.kholodilin.repogrowth.search.domain.SearchQuery;
import com.kholodilin.repogrowth.search.persistence.SearchRunJdbcRepository;
import com.kholodilin.repogrowth.support.AbstractPostgresTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchQueryServiceIT extends AbstractPostgresTest {

    @Autowired
    SearchQueryService searchQueryService;
    @Autowired
    GitHubOwnerJdbcRepository ownerJdbcRepository;
    @Autowired
    RepositoryJdbcRepository repositoryJdbcRepository;
    @Autowired
    SearchRunJdbcRepository searchRunRepository;
    @Autowired
    PlanningWindow planningWindow;
    @Autowired
    JdbcClient jdbcClient;

    private Repository kafka;
    private Repository outbox;

    @BeforeEach
    void seed() {
        jdbcClient.sql("DELETE FROM search_result").update();
        jdbcClient.sql("DELETE FROM search_run").update();
        jdbcClient.sql("DELETE FROM search_query").update();
        jdbcClient.sql("DELETE FROM growth_event").update();
        jdbcClient.sql("DELETE FROM growth_event_setting").update();
        jdbcClient.sql("DELETE FROM growth_event_state").update();
        jdbcClient.sql("DELETE FROM repository_health").update();
        jdbcClient.sql("DELETE FROM repository_topics").update();
        jdbcClient.sql("DELETE FROM repository").update();
        jdbcClient.sql("DELETE FROM github_owner").update();
        GitHubOwner owner = ownerJdbcRepository.upsert(100L, "acme", OwnerType.USER, null, "https://github.com/acme");
        kafka = track(owner, 301L, "kafka-starter", "acme/kafka-starter");
        outbox = track(owner, 302L, "spring-outbox", "acme/spring-outbox");
    }

    @Test
    void rejectsDuplicateQueryForTheSameRepository() {
        searchQueryService.create(kafka.id(), null, "spring boot transactional outbox", true, 50);

        assertThatThrownBy(() -> searchQueryService.create(kafka.id(), null, "  Spring Boot   transactional outbox ", true, 50))
                .isInstanceOf(ApiException.class)
                .hasMessage("This search query is already tracked for the repository");
        assertThat(searchQueryService.list(kafka.id())).hasSize(1);
    }

    @Test
    void allowsTheSameQueryOnAnotherRepository() {
        searchQueryService.create(kafka.id(), null, "outbox kafka language:Java", true, 50);
        searchQueryService.create(outbox.id(), null, "outbox kafka language:Java", true, 50);
        assertThat(searchQueryService.list(kafka.id())).hasSize(1);
        assertThat(searchQueryService.list(outbox.id())).hasSize(1);
    }

    @Test
    void runAllPlansARunForEachQuery() {
        searchQueryService.create(kafka.id(), null, "outbox", true, 50);
        searchQueryService.create(kafka.id(), null, "kafka outbox", true, 50);
        searchQueryService.create(outbox.id(), null, "other repo query", true, 50);

        List<Long> runIds = searchQueryService.runAll(kafka.id());
        assertThat(runIds).hasSize(2);
        assertThat(searchQueryService.runAll(kafka.id())).containsExactlyInAnyOrderElementsOf(runIds);
    }

    @Test
    void reRunningTodayKeepsTheStoredSnapshotUntilANewOneArrives() {
        SearchQuery query = searchQueryService.create(kafka.id(), null, "transactional outbox postgresql", true, 50);
        LocalDate today = planningWindow.businessDate();
        storeSnapshot(query.id(), today.minusDays(2), 3, 433);
        storeSnapshot(query.id(), today.minusDays(1), 2, 441);
        storeSnapshot(query.id(), today, 2, 443);

        SearchQueryService.SearchHistory before = searchQueryService.history(query.id());
        assertThat(before.currentRank()).isEqualTo(2);
        assertThat(before.change().kind()).isEqualTo(RankChangeMath.Kind.UNCHANGED);
        assertThat(before.totalResults()).isEqualTo(443);

        searchQueryService.runNow(query.id());

        SearchQueryService.SearchHistory during = searchQueryService.history(query.id());
        assertThat(during.searchStatus()).isEqualTo("READY");
        assertThat(during.currentRank()).isEqualTo(2);
        assertThat(during.change().kind()).isEqualTo(RankChangeMath.Kind.UNCHANGED);
        assertThat(during.change().amount()).isZero();
        assertThat(during.totalResults()).isEqualTo(443);
        assertThat(during.bestRank()).isEqualTo(2);
        assertThat(during.points()).hasSize(3);
        assertThat(during.lastChecked()).isEqualTo(before.lastChecked());
    }

    @Test
    void aFailedReRunLeavesTheLastStoredSnapshotOnTheRow() {
        SearchQuery query = searchQueryService.create(kafka.id(), null, "transactional outbox kafka", true, 50);
        LocalDate today = planningWindow.businessDate();
        storeSnapshot(query.id(), today.minusDays(1), 4, 460);
        storeSnapshot(query.id(), today, 3, 466);

        long runId = searchQueryService.runNow(query.id());
        searchRunRepository.markFailed(runId, "RATE_LIMIT", "secondary rate limit");

        SearchQueryService.SearchHistory after = searchQueryService.history(query.id());
        assertThat(after.searchStatus()).isEqualTo("FAILED");
        assertThat(after.currentRank()).isEqualTo(3);
        assertThat(after.change().kind()).isEqualTo(RankChangeMath.Kind.IMPROVED);
        assertThat(after.change().amount()).isEqualTo(1);
        assertThat(after.totalResults()).isEqualTo(466);
    }

    private void storeSnapshot(long searchQueryId, LocalDate businessDate, int position, int totalCount) {
        Instant collectedAt = businessDate.atTime(9, 30).toInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
        jdbcClient.sql("""
                        INSERT INTO search_run (
                            search_query_id, repository_id, business_date, status,
                            started_at, completed_at, snapshot_at, total_count, tracked_repository_position
                        )
                        VALUES (
                            :searchQueryId, :repositoryId, :businessDate, 'SUCCESS',
                            :collectedAt, :collectedAt, :collectedAt, :totalCount, :position
                        )
                        """)
                .param("searchQueryId", searchQueryId)
                .param("repositoryId", kafka.id())
                .param("businessDate", businessDate)
                .param("collectedAt", SqlTime.ts(collectedAt))
                .param("totalCount", totalCount)
                .param("position", position)
                .update();
    }

    private Repository track(GitHubOwner owner, long githubId, String name, String fullName) {
        Repository repository = repositoryJdbcRepository.upsertKeepingTracking(new Repository(
                null, githubId, owner.id(), name, fullName, name, "PUBLIC", "main", "Java",
                false, false, 1, 0, 0, 0, 0, false,
                Instant.parse("2024-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"),
                null, null, null, null, null, null
        ));
        repositoryJdbcRepository.markAccountAccessible(repository.id());
        repositoryJdbcRepository.setTracking(repository.id(), true);
        return repositoryJdbcRepository.findById(repository.id()).orElseThrow();
    }
}
