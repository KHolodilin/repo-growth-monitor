package com.kholodilin.repogrowth.repository.application;

import com.kholodilin.repogrowth.repository.domain.Repository;
import com.kholodilin.repogrowth.repository.domain.RepositoryHealthFacts;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RepositoryHealthEvaluatorTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-08-28T12:00:00Z"), ZoneOffset.UTC);
    private final RepositoryHealthEvaluator evaluator = new RepositoryHealthEvaluator(clock);

    @Test
    void allChecksPassForCompleteRepository() {
        Repository repository = new Repository(
                1L, 1002L, 1L, "kafka-starter", "acme/kafka-starter",
                "Production-ready Transactional Outbox Pattern for Spring Boot 4 with Kafka and PostgreSQL.",
                "PRIVATE", "main", "Java", false, false, 41, 12, 6, 2, 3, true,
                Instant.parse("2024-01-08T09:00:00Z"), Instant.parse("2026-08-26T18:00:00Z"),
                Instant.parse("2026-08-26T18:00:00Z"), Instant.parse("2026-08-26T07:54:00Z"),
                Instant.parse("2026-06-01T00:00:00Z"), null, Instant.now(clock), Instant.now(clock)
        );
        RepositoryHealthFacts facts = new RepositoryHealthFacts(
                "https://docs.acme.dev/kafka-starter",
                true, true, true, true, true, true, true, true, true
        );
        var health = evaluator.evaluate(repository, List.of("kafka", "outbox", "spring-boot"), 2, facts);
        assertThat(health.discoverability()).allMatch(item -> item.passed());
        assertThat(health.communityStandards()).allMatch(item -> item.passed());
        assertThat(health.discoverability()).hasSize(12);
        assertThat(health.communityStandards()).hasSize(5);
    }

    @Test
    void failedChecksWhenFactsMissing() {
        Repository repository = new Repository(
                1L, 1L, 1L, "demo", "acme/demo", "short",
                "PUBLIC", "main", "Java", false, true, 0, 0, 0, 0, 0, true,
                Instant.parse("2024-01-01T00:00:00Z"), Instant.parse("2024-01-01T00:00:00Z"),
                null, Instant.parse("2024-01-01T00:00:00Z"),
                null, null, Instant.now(clock), Instant.now(clock)
        );
        var health = evaluator.evaluate(repository, List.of(), 0, RepositoryHealthFacts.empty());
        assertThat(health.discoverability()).filteredOn(item -> item.passed()).extracting(item -> item.label())
                .containsExactly("Repository description exists");
        assertThat(health.communityStandards()).noneMatch(item -> item.passed());
    }
}
