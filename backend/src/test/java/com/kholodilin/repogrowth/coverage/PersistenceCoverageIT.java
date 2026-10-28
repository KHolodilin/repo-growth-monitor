package com.kholodilin.repogrowth.coverage;

import com.kholodilin.repogrowth.github.client.GitHubClient;
import com.kholodilin.repogrowth.github.model.GitHubOwnerResponse;
import com.kholodilin.repogrowth.github.model.GitHubRepositoryResponse;
import com.kholodilin.repogrowth.repository.application.RepositoryHealthService;
import com.kholodilin.repogrowth.repository.application.RepositoryService;
import com.kholodilin.repogrowth.repository.domain.GitHubOwner;
import com.kholodilin.repogrowth.repository.domain.OwnerType;
import com.kholodilin.repogrowth.repository.domain.Repository;
import com.kholodilin.repogrowth.repository.domain.RepositoryHealthFacts;
import com.kholodilin.repogrowth.repository.persistence.GitHubOwnerJdbcRepository;
import com.kholodilin.repogrowth.repository.persistence.RepositoryJdbcRepository;
import com.kholodilin.repogrowth.search.domain.ActivityStatus;
import com.kholodilin.repogrowth.search.domain.SearchQuery;
import com.kholodilin.repogrowth.search.domain.SearchResult;
import com.kholodilin.repogrowth.search.domain.SearchRunStatus;
import com.kholodilin.repogrowth.search.persistence.SearchQueryJdbcRepository;
import com.kholodilin.repogrowth.search.persistence.SearchResultJdbcRepository;
import com.kholodilin.repogrowth.search.persistence.SearchRunJdbcRepository;
import com.kholodilin.repogrowth.support.AbstractPostgresTest;
import com.kholodilin.repogrowth.topic.domain.TopicResult;
import com.kholodilin.repogrowth.topic.persistence.TopicResultJdbcRepository;
import com.kholodilin.repogrowth.topic.persistence.TopicRunJdbcRepository;
import com.kholodilin.repogrowth.topic.persistence.TopicWatchJdbcRepository;
import com.kholodilin.repogrowth.traffic.persistence.TrafficJdbcRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

class PersistenceCoverageIT extends AbstractPostgresTest {

    @MockitoBean
    GitHubClient gitHubClient;

    @Autowired
    RepositoryService repositoryService;
    @Autowired
    RepositoryHealthService healthService;
    @Autowired
    GitHubOwnerJdbcRepository ownerJdbcRepository;
    @Autowired
    RepositoryJdbcRepository repositoryJdbcRepository;
    @Autowired
    SearchQueryJdbcRepository searchQueryJdbcRepository;
    @Autowired
    SearchRunJdbcRepository searchRunJdbcRepository;
    @Autowired
    SearchResultJdbcRepository searchResultJdbcRepository;
    @Autowired
    TopicWatchJdbcRepository topicWatchJdbcRepository;
    @Autowired
    TopicRunJdbcRepository topicRunJdbcRepository;
    @Autowired
    TopicResultJdbcRepository topicResultJdbcRepository;
    @Autowired
    TrafficJdbcRepository trafficJdbcRepository;
    @Autowired
    JdbcClient jdbcClient;

    private Repository repository;

    @BeforeEach
    void seed() {
        wipeRepositoryData(jdbcClient);
        GitHubOwner owner = ownerJdbcRepository.upsert(100L, "acme", OwnerType.USER, null, "https://github.com/acme");
        repository = repositoryJdbcRepository.upsertKeepingTracking(new Repository(
                null, 200L, owner.id(), "demo", "acme/demo", "demo repository description", "PUBLIC", "main", "Java",
                false, false, 10, 1, 2, 0, 3, false,
                Instant.parse("2024-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-09-01T00:00:00Z"), Instant.parse("2026-09-01T00:00:00Z"), null, null, null, null
        ));
        repositoryJdbcRepository.markAccountAccessible(repository.id());
        repositoryJdbcRepository.setTracking(repository.id(), true);
        repository = repositoryJdbcRepository.findById(repository.id()).orElseThrow();
    }

    @Test
    void repositoryReadsWritesAndHealthScore() {
        assertThat(repositoryJdbcRepository.findByGithubId(200L)).isPresent();
        assertThat(repositoryJdbcRepository.findAll()).hasSize(1);
        assertThat(repositoryJdbcRepository.findAccountAccessible()).hasSize(1);
        assertThat(repositoryJdbcRepository.isAccountAccessible(repository.id())).isTrue();
        assertThat(repositoryJdbcRepository.countTracked()).isEqualTo(1);
        repositoryJdbcRepository.updateStats(
                repository.id(), "updated about", 11, 2, 3, 1, 4,
                Instant.parse("2026-09-02T00:00:00Z"), Instant.parse("2026-09-02T00:00:00Z"),
                Instant.parse("2026-09-02T00:00:00Z"), Instant.parse("2026-09-02T00:00:00Z"), false
        );
        repositoryJdbcRepository.updateLastReleaseAt(repository.id(), Instant.parse("2026-08-01T00:00:00Z"));
        repositoryJdbcRepository.upsertHealth(repository.id(), new RepositoryHealthFacts(
                "https://example.com", true, true, true, true, true, true, true, true, true, true
        ));
        assertThat(repositoryJdbcRepository.findHealth(repository.id())).isPresent();
        assertThat(healthService.discoverabilityPassed(repository)).isNotNull();
        assertThat(healthService.discoverabilityPassed(repositoryService.get(repository.id()))).isPositive();
        assertThat(repositoryService.owner(repository.ownerId()).login()).isEqualTo("acme");
        assertThat(repositoryService.topics(repository.id())).isEmpty();
        assertThat(repositoryService.tracked()).hasSize(1);
        assertThat(repositoryService.list(false)).hasSize(1);
        repositoryJdbcRepository.setTrafficPermissionMissing(repository.id(), true);
        assertThat(repositoryJdbcRepository.signalsFor(List.of(repository.id())).get(repository.id()).trafficPermissionMissing()).isTrue();
        assertThat(repositoryJdbcRepository.signalsFor(List.of())).isEmpty();
        repositoryJdbcRepository.markInaccessibleExcept(List.of());
        assertThat(repositoryJdbcRepository.isAccountAccessible(repository.id())).isFalse();
        assertThatThrownBy(() -> repositoryService.get(999_999L)).hasMessageContaining("Repository");
        assertThatThrownBy(() -> repositoryService.owner(999_999L)).hasMessageContaining("Owner");
    }

    @Test
    void refreshPullsAccessibleRepositories() {
        when(gitHubClient.listAccessibleRepositories()).thenReturn(List.of(
                remote(300L, "extra", "acme/extra", new GitHubOwnerResponse(100L, "acme", "User", null, null)),
                remote(301L, "org-repo", "org/org-repo", new GitHubOwnerResponse(400L, "org", "Organization", "https://img", "https://github.com/org"))
        ));
        assertThat(repositoryService.list(true)).extracting(Repository::fullName).contains("acme/extra", "org/org-repo");
        assertThat(repositoryJdbcRepository.isAccountAccessible(repository.id())).isFalse();
        Repository disabled = repositoryService.setTracking(repository.id(), false);
        assertThat(disabled.trackingEnabled()).isFalse();
        assertThatThrownBy(() -> repositoryService.setTracking(repository.id(), true))
                .hasMessageContaining("Only repositories available");
    }

    @Test
    void searchAndTopicRunsRoundTrip() {
        SearchQuery query = searchQueryJdbcRepository.insert(repository.id(), "outbox", "outbox kafka", true, 50);
        assertThat(searchQueryJdbcRepository.update(query.id(), "named", "outbox kafka", true, 40).resultLimit()).isEqualTo(40);
        assertThat(searchQueryJdbcRepository.findById(query.id())).isPresent();
        assertThat(searchQueryJdbcRepository.findNormalized(repository.id(), "outbox kafka", null)).isPresent();
        assertThat(searchQueryJdbcRepository.findNormalized(repository.id(), "outbox kafka", query.id())).isEmpty();
        assertThat(searchQueryJdbcRepository.findEnabled()).hasSize(1);
        assertThat(searchQueryJdbcRepository.countByRepository(repository.id())).isEqualTo(1);

        LocalDate today = LocalDate.of(2026, 9, 23);
        searchRunJdbcRepository.insertIgnore(query.id(), repository.id(), today.minusDays(1));
        searchRunJdbcRepository.insertIgnore(query.id(), repository.id(), today);
        searchRunJdbcRepository.markMissedDays(today, 30);
        var claimed = searchRunJdbcRepository.claim("worker", Duration.ofMinutes(5)).orElseThrow();
        searchRunJdbcRepository.markSuccess(claimed.id(), 10, 2);
        searchRunJdbcRepository.markEnrichment(claimed.id(), "SUCCESS");
        searchRunJdbcRepository.requeueCompleted(claimed.id());
        searchRunJdbcRepository.markRetry(claimed.id(), Instant.parse("2026-09-23T07:00:00Z"), "RATE", "slow");
        searchRunJdbcRepository.markFailed(claimed.id(), "RATE", "slow");
        searchRunJdbcRepository.releaseClaim(claimed.id(), SearchRunStatus.READY);
        assertThat(searchRunJdbcRepository.find(query.id(), today.minusDays(1))
                .or(() -> searchRunJdbcRepository.find(query.id(), today))).isPresent();
        assertThat(searchRunJdbcRepository.findById(claimed.id())).isPresent();
        assertThat(searchRunJdbcRepository.snapshots(query.id())).isNotNull();
        assertThat(searchRunJdbcRepository.previousSnapshot(query.id(), today)).isNotNull();
        assertThat(searchRunJdbcRepository.latest(query.id())).isPresent();
        assertThat(searchRunJdbcRepository.latestSnapshot(query.id())).isNotNull();
        assertThat(searchRunJdbcRepository.missedDates(query.id())).isNotNull();
        assertThat(searchRunJdbcRepository.countByStatus(SearchRunStatus.READY)).isNotNegative();

        SearchResult row = new SearchResult(
                null, claimed.id(), 1, 200L, "acme/demo", "acme", 10, 1, 2, 3, "Java", "demo",
                "https://github.com/acme/demo", Instant.parse("2024-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-09-01T00:00:00Z"), ActivityStatus.ACTIVE, Instant.parse("2026-09-23T00:00:00Z")
        );
        SearchResult blank = new SearchResult(
                null, claimed.id(), 2, 201L, "acme/other", "acme", 1, 0, 0, 0, null, null,
                "https://github.com/acme/other", null, null, null, null, null
        );
        searchResultJdbcRepository.replaceAll(claimed.id(), List.of(row, blank));
        SearchResult stored = searchResultJdbcRepository.findByRun(claimed.id()).getFirst();
        searchResultJdbcRepository.updateSnapshot(new SearchResult(
                stored.id(), stored.searchRunId(), stored.position(), stored.githubRepositoryId(), stored.fullName(),
                stored.owner(), 12, 2, 3, 4, "Java", "updated", stored.htmlUrl(),
                stored.repositoryCreatedAt(), stored.repositoryUpdatedAt(), stored.activityAt(),
                ActivityStatus.LOW_ACTIVITY, stored.metadataUpdatedAt()
        ));
        assertThat(searchResultJdbcRepository.findByRun(claimed.id()).getFirst().stars()).isEqualTo(12);
        searchQueryJdbcRepository.delete(query.id());

        var watch = topicWatchJdbcRepository.insert(repository.id(), "kafka", null, 50);
        topicRunJdbcRepository.insertIgnore(watch.id(), repository.id(), today);
        topicRunJdbcRepository.markMissedDays(today, 30);
        var topicClaimed = topicRunJdbcRepository.claim("topic", Duration.ofMinutes(5)).orElseThrow();
        topicRunJdbcRepository.markSuccess(topicClaimed.id(), 8, 1);
        topicRunJdbcRepository.requeueCompleted(topicClaimed.id());
        topicRunJdbcRepository.markRetry(topicClaimed.id(), Instant.parse("2026-09-23T08:00:00Z"), "RATE", "slow");
        topicRunJdbcRepository.markFailed(topicClaimed.id(), "RATE", "slow");
        topicRunJdbcRepository.releaseClaim(topicClaimed.id(), SearchRunStatus.RETRY);
        assertThat(topicRunJdbcRepository.find(watch.id(), today)).isPresent();
        assertThat(topicRunJdbcRepository.findById(topicClaimed.id())).isPresent();
        assertThat(topicRunJdbcRepository.snapshots(watch.id())).isNotNull();
        assertThat(topicRunJdbcRepository.previousSnapshot(watch.id(), today.plusDays(1))).isNotNull();
        assertThat(topicRunJdbcRepository.latest(watch.id())).isPresent();
        assertThat(topicRunJdbcRepository.latestSnapshot(watch.id())).isNotNull();
        assertThat(topicRunJdbcRepository.missedDates(watch.id())).isNotNull();
        topicResultJdbcRepository.replaceAll(topicClaimed.id(), List.of(new TopicResult(
                null, topicClaimed.id(), 1, 200L, "acme/demo", "acme", 10, 1, 2, "Java", "demo", "https://github.com/acme/demo"
        )));
        assertThat(topicResultJdbcRepository.findByRun(topicClaimed.id())).hasSize(1);
    }

    @Test
    void trafficReadsEmptyAndFilledRanges() {
        LocalDate today = LocalDate.of(2026, 9, 23);
        assertThat(trafficJdbcRepository.history(repository.id(), null)).isEmpty();
        assertThat(trafficJdbcRepository.latestDate(repository.id())).isEmpty();
        assertThat(trafficJdbcRepository.totals(repository.id(), null).views()).isZero();
        assertThat(trafficJdbcRepository.portfolioTotals(null).views()).isZero();
        assertThat(trafficJdbcRepository.portfolioTotalsInRange(today.minusDays(7), today).views()).isZero();
        assertThat(trafficJdbcRepository.earliestReferrerSnapshotDate(repository.id())).isEmpty();
        assertThat(trafficJdbcRepository.latestReferrerSnapshot(repository.id()).rows()).isEmpty();
        assertThat(trafficJdbcRepository.latestPathSnapshot(repository.id(), true).rows()).isEmpty();
        assertThat(trafficJdbcRepository.latestPathSnapshot(repository.id(), false).rows()).isEmpty();

        trafficJdbcRepository.upsertDaily(repository.id(), today, 8, 3, 2, 1);
        trafficJdbcRepository.upsertDailyStats(repository.id(), today, 10, 1, 2, 0, 3);
        trafficJdbcRepository.replaceContributorLogins(repository.id(), today, java.util.List.of("alice", "bob"));
        assertThat(trafficJdbcRepository.trackedDailyStats(today, today).getFirst().contributorLogins())
                .containsExactly("alice", "bob");
        assertThat(trafficJdbcRepository.latestTrackedDailyStats().getFirst().contributorLogins())
                .containsExactly("alice", "bob");
        trafficJdbcRepository.replaceContributorLogins(repository.id(), today, java.util.List.of());
        assertThat(trafficJdbcRepository.trackedDailyStats(today, today).getFirst().contributorLogins()).isEmpty();
        trafficJdbcRepository.insertReferrers(repository.id(), today, Instant.parse("2026-09-23T00:00:00Z"), "github.com", 4, 2);
        trafficJdbcRepository.insertPath(repository.id(), today, Instant.parse("2026-09-23T00:00:00Z"), "/acme/demo", "demo", 4, 2, false);
        trafficJdbcRepository.insertPath(repository.id(), today, Instant.parse("2026-09-23T00:00:00Z"), "/acme/demo/issues", "issues", 1, 1, true);
        assertThat(trafficJdbcRepository.history(repository.id(), today.minusDays(1))).hasSize(1);
        assertThat(trafficJdbcRepository.dailyStatsHistory(repository.id(), null)).hasSize(1);
        assertThat(trafficJdbcRepository.earliestDailyStatsDate(repository.id())).contains(today);
        assertThat(trafficJdbcRepository.totalsByTrackedRepository(today.minusDays(1), today)).isNotEmpty();
        assertThat(trafficJdbcRepository.dailyPortfolio(today.minusDays(1), today)).isNotEmpty();
        assertThat(trafficJdbcRepository.hasTrackedTraffic()).isTrue();
        assertThat(trafficJdbcRepository.earliestTrackedTrafficDate()).contains(today);
        assertThat(trafficJdbcRepository.starsChangeSince(today.minusDays(30))).isNull();
        assertThat(trafficJdbcRepository.referrerSnapshotsForDelta(repository.id(), today.minusDays(1), today)).isNotEmpty();
        assertThat(trafficJdbcRepository.pathSnapshotsForDelta(repository.id(), today.minusDays(1), today, false)).isNotEmpty();
        assertThat(trafficJdbcRepository.latestPathSnapshot(repository.id(), false).rows()).hasSize(1);
        trafficJdbcRepository.deleteReferrerSnapshot(repository.id(), today);
        trafficJdbcRepository.deletePathSnapshot(repository.id(), today);
        assertThat(trafficJdbcRepository.latestReferrerSnapshot(repository.id()).rows()).isEmpty();
    }

    private static GitHubRepositoryResponse remote(long id, String name, String fullName, GitHubOwnerResponse owner) {
        return new GitHubRepositoryResponse(
                id, name, fullName, "desc", false, "public", "main", "Java",
                false, false, 1, 0, 0, 0, "https://github.com/" + fullName,
                Instant.parse("2024-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"),
                owner, List.of("kafka"), null, null
        );
    }
}
