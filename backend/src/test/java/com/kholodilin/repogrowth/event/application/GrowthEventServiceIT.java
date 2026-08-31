package com.kholodilin.repogrowth.event.application;

import com.kholodilin.repogrowth.common.api.ApiException;
import com.kholodilin.repogrowth.event.detect.CandidateEvent;
import com.kholodilin.repogrowth.event.domain.GrowthEvent;
import com.kholodilin.repogrowth.event.domain.GrowthEventCatalog;
import com.kholodilin.repogrowth.event.domain.GrowthEventSetting;
import com.kholodilin.repogrowth.event.persistence.GrowthEventJdbcRepository;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GrowthEventServiceIT extends AbstractPostgresTest {

    @Autowired
    GrowthEventService growthEventService;
    @Autowired
    GrowthEventJdbcRepository eventJdbcRepository;
    @Autowired
    GitHubOwnerJdbcRepository ownerJdbcRepository;
    @Autowired
    RepositoryJdbcRepository repositoryJdbcRepository;
    @Autowired
    JdbcClient jdbcClient;

    private Repository repository;

    @BeforeEach
    void seed() {
        jdbcClient.sql("DELETE FROM growth_event").update();
        jdbcClient.sql("DELETE FROM growth_event_setting").update();
        jdbcClient.sql("DELETE FROM growth_event_state").update();
        jdbcClient.sql("DELETE FROM search_result").update();
        jdbcClient.sql("DELETE FROM search_run").update();
        jdbcClient.sql("DELETE FROM search_query").update();
        jdbcClient.sql("DELETE FROM collection_job").update();
        jdbcClient.sql("DELETE FROM collection_run").update();
        jdbcClient.sql("DELETE FROM traffic_path_snapshot").update();
        jdbcClient.sql("DELETE FROM traffic_referrer_snapshot").update();
        jdbcClient.sql("DELETE FROM traffic_daily").update();
        jdbcClient.sql("DELETE FROM repository_daily_stats").update();
        jdbcClient.sql("DELETE FROM repository_health").update();
        jdbcClient.sql("DELETE FROM repository_topics").update();
        jdbcClient.sql("DELETE FROM repository").update();
        jdbcClient.sql("DELETE FROM github_owner").update();
        GitHubOwner owner = ownerJdbcRepository.upsert(100L, "acme", OwnerType.USER, null, "https://github.com/acme");
        repository = repositoryJdbcRepository.upsertKeepingTracking(new Repository(
                null, 200L, owner.id(), "demo", "acme/demo", "demo repo", "PUBLIC", "main", "Java",
                false, false, 10, 0, 2, 1, 0, false, Instant.parse("2024-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"),
                null, null, null, null, null, null
        ));
        repositoryJdbcRepository.markAccountAccessible(repository.id());
        repositoryJdbcRepository.setTracking(repository.id(), true);
        repository = repositoryJdbcRepository.findById(repository.id()).orElseThrow();
    }

    @Test
    void createsUpdatesAndDeletesManualEventsOnly() {
        GrowthEvent created = growthEventService.createManual(
                repository.id(),
                GrowthEventCatalog.LINKEDIN_POST,
                Instant.parse("2026-08-20T10:00:00Z"),
                "Posted on LinkedIn",
                "https://linkedin.com/posts/1",
                "Launch"
        );
        assertThat(created.source()).isEqualTo(GrowthEventCatalog.SOURCE_MANUAL);
        assertThat(created.category()).isEqualTo(GrowthEventCatalog.CATEGORY_PROMOTION);

        GrowthEvent updated = growthEventService.updateManual(
                created.id(),
                GrowthEventCatalog.REDDIT_POST,
                Instant.parse("2026-08-21T10:00:00Z"),
                "Posted on Reddit",
                "",
                "Show HN"
        );
        assertThat(updated.type()).isEqualTo(GrowthEventCatalog.REDDIT_POST);
        assertThat(updated.url()).isNull();
        assertThat(updated.description()).isEqualTo("Show HN");

        List<GrowthEvent> listed = growthEventService.list(repository.id(), "all");
        assertThat(listed).extracting(GrowthEvent::title).containsExactly("Posted on Reddit");

        growthEventService.deleteManual(created.id());
        assertThat(growthEventService.list(repository.id(), "all")).isEmpty();
    }

    @Test
    void rejectsUnknownManualTypeAndAutomaticEdit() {
        assertThatThrownBy(() -> growthEventService.createManual(
                repository.id(),
                GrowthEventCatalog.DESCRIPTION_CHANGED,
                Instant.now(),
                "Nope",
                null,
                null
        )).isInstanceOf(ApiException.class);

        CandidateEvent automatic = new CandidateEvent(
                Instant.parse("2026-08-20T00:00:00Z"),
                GrowthEventCatalog.CATEGORY_DISCOVERABILITY,
                GrowthEventCatalog.DESCRIPTION_CHANGED,
                "Repository description changed",
                "new",
                "https://github.com/acme/demo",
                GrowthEventCatalog.SOURCE_GITHUB,
                "description:new"
        );
        GrowthEvent stored = eventJdbcRepository.insertIgnore(repository.id(), automatic).orElseThrow();
        assertThatThrownBy(() -> growthEventService.updateManual(stored.id(), null, null, "edit", null, null))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> growthEventService.deleteManual(stored.id()))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void uniqueExternalIdIsIdempotent() {
        CandidateEvent candidate = new CandidateEvent(
                Instant.parse("2026-08-20T00:00:00Z"),
                GrowthEventCatalog.CATEGORY_RELEASE,
                GrowthEventCatalog.RELEASE_PUBLISHED,
                "Release v1",
                "v1.0.0",
                "https://github.com/acme/demo/releases/tag/v1.0.0",
                GrowthEventCatalog.SOURCE_GITHUB,
                "release:55"
        );
        Optional<GrowthEvent> first = eventJdbcRepository.insertIgnore(repository.id(), candidate);
        Optional<GrowthEvent> second = eventJdbcRepository.insertIgnore(repository.id(), candidate);
        assertThat(first).isPresent();
        assertThat(second).isEmpty();
        assertThat(eventJdbcRepository.findRecent(repository.id(), 10)).hasSize(1);
    }

    @Test
    void settingsDefaultsAndPartialUpdate() {
        List<GrowthEventSetting> defaults = growthEventService.settings(repository.id());
        assertThat(defaults).hasSize(GrowthEventCatalog.automaticDefaults().size());
        assertThat(defaults.stream().filter(item -> item.eventType().equals(GrowthEventCatalog.HOMEPAGE_CHANGED)))
                .allMatch(item -> !item.enabled());
        assertThat(defaults.stream().filter(item -> item.eventType().equals(GrowthEventCatalog.README_SIGNIFICANTLY_CHANGED)))
                .allMatch(GrowthEventSetting::enabled);

        List<GrowthEventSetting> updated = growthEventService.updateSettings(
                repository.id(),
                List.of(new GrowthEventSetting(repository.id(), GrowthEventCatalog.HOMEPAGE_CHANGED, true, null, null))
        );
        assertThat(updated.stream().filter(item -> item.eventType().equals(GrowthEventCatalog.HOMEPAGE_CHANGED)))
                .allMatch(GrowthEventSetting::enabled);
        assertThat(updated.stream().filter(item -> item.eventType().equals(GrowthEventCatalog.README_SIGNIFICANTLY_CHANGED)))
                .allMatch(GrowthEventSetting::enabled);
    }
}
