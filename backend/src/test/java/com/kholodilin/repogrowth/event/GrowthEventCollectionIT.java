package com.kholodilin.repogrowth.event;

import com.kholodilin.repogrowth.collection.collector.CollectionContext;
import com.kholodilin.repogrowth.collection.collector.GrowthEventsCollector;
import com.kholodilin.repogrowth.collection.domain.CollectionJob;
import com.kholodilin.repogrowth.collection.domain.CollectionJobStatus;
import com.kholodilin.repogrowth.collection.domain.CollectionJobType;
import com.kholodilin.repogrowth.event.domain.GrowthEvent;
import com.kholodilin.repogrowth.event.domain.GrowthEventCatalog;
import com.kholodilin.repogrowth.event.persistence.GrowthEventJdbcRepository;
import com.kholodilin.repogrowth.event.persistence.GrowthEventSettingJdbcRepository;
import com.kholodilin.repogrowth.github.client.GitHubClient;
import com.kholodilin.repogrowth.github.model.GitHubLicenseResponse;
import com.kholodilin.repogrowth.github.model.GitHubOwnerResponse;
import com.kholodilin.repogrowth.github.model.GitHubReadmeResponse;
import com.kholodilin.repogrowth.github.model.GitHubRepositoryResponse;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

class GrowthEventCollectionIT extends AbstractPostgresTest {

    @MockitoBean
    GitHubClient gitHubClient;

    @Autowired
    GrowthEventsCollector growthEventsCollector;
    @Autowired
    GrowthEventJdbcRepository eventJdbcRepository;
    @Autowired
    GrowthEventSettingJdbcRepository settingJdbcRepository;
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
        GitHubOwner owner = ownerJdbcRepository.upsert(100L, "acme", OwnerType.USER, null, "https://github.com/acme");
        repository = repositoryJdbcRepository.upsertKeepingTracking(new Repository(
                null, 200L, owner.id(), "demo", "acme/demo", "old description", "PUBLIC", "main", "Java",
                false, false, 10, 0, 2, 1, 0, false, Instant.parse("2024-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"),
                null, null, null, null, null, null
        ));
        repositoryJdbcRepository.markAccountAccessible(repository.id());
        repositoryJdbcRepository.setTracking(repository.id(), true);
        repository = repositoryJdbcRepository.findById(repository.id()).orElseThrow();
        stubGithub("old description");
    }

    @Test
    void firstCollectIsBaselineThenDescriptionChangeIsRecorded() {
        collect();
        assertThat(eventJdbcRepository.findRecent(repository.id(), 20)).isEmpty();

        stubGithub("new description");
        collect();
        List<GrowthEvent> events = eventJdbcRepository.findRecent(repository.id(), 20);
        assertThat(events).extracting(GrowthEvent::type).containsExactly(GrowthEventCatalog.DESCRIPTION_CHANGED);
    }

    @Test
    void disabledTypeIsNotInserted() {
        collect();
        settingJdbcRepository.replace(
                repository.id(),
                java.util.Map.of(GrowthEventCatalog.DESCRIPTION_CHANGED, false)
        );
        stubGithub("changed again");
        collect();
        assertThat(eventJdbcRepository.findRecent(repository.id(), 20)).isEmpty();
    }

    private void collect() {
        CollectionJob job = new CollectionJob(
                1L, 1L, repository.id(), CollectionJobType.GROWTH_EVENTS, LocalDate.parse("2026-08-31"),
                CollectionJobStatus.RUNNING, 1, null, null, null, Instant.now(), null, null, null
        );
        growthEventsCollector.collect(new CollectionContext(job, repository, "acme"));
    }

    private void stubGithub(String description) {
        GitHubOwnerResponse owner = new GitHubOwnerResponse(100L, "acme", "User", null, null);
        when(gitHubClient.getRepository(anyString(), anyString())).thenReturn(new GitHubRepositoryResponse(
                200L, "demo", "acme/demo", description, false, "public", "main", "Java", false, false,
                10, 5, 2, 1, "https://github.com/acme/demo", Instant.parse("2024-01-01T00:00:00Z"), Instant.now(), Instant.now(), owner,
                List.of("java"), null, new GitHubLicenseResponse("mit", "MIT License", "MIT")
        ));
        when(gitHubClient.getReadmeDetails(anyString(), anyString()))
                .thenReturn(Optional.of(new GitHubReadmeResponse("sha1", "# demo", "utf-8", "README.md")));
        when(gitHubClient.listIssues(anyString(), anyString())).thenReturn(List.of());
        when(gitHubClient.listPulls(anyString(), anyString())).thenReturn(List.of());
        when(gitHubClient.listReleases(anyString(), anyString())).thenReturn(List.of());
        when(gitHubClient.listContributors(anyString(), anyString())).thenReturn(List.of());
    }
}
