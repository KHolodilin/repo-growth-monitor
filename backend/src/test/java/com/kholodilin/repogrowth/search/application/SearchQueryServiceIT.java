package com.kholodilin.repogrowth.search.application;

import com.kholodilin.repogrowth.common.api.ApiException;
import com.kholodilin.repogrowth.repository.domain.GitHubOwner;
import com.kholodilin.repogrowth.repository.domain.OwnerType;
import com.kholodilin.repogrowth.repository.domain.Repository;
import com.kholodilin.repogrowth.repository.persistence.GitHubOwnerJdbcRepository;
import com.kholodilin.repogrowth.repository.persistence.RepositoryJdbcRepository;
import com.kholodilin.repogrowth.support.AbstractPostgresTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
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
    JdbcClient jdbcClient;

    private Repository kafka;
    private Repository outbox;

    @BeforeEach
    void seed() {
        jdbcClient.sql("DELETE FROM search_result").update();
        jdbcClient.sql("DELETE FROM search_run").update();
        jdbcClient.sql("DELETE FROM search_query").update();
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
