package com.kholodilin.repogrowth.search.application;

import com.kholodilin.repogrowth.common.config.SearchProperties;
import com.kholodilin.repogrowth.github.client.GitHubClient;
import com.kholodilin.repogrowth.github.exception.GitHubException;
import com.kholodilin.repogrowth.github.model.GitHubLicenseResponse;
import com.kholodilin.repogrowth.github.model.GitHubOwnerResponse;
import com.kholodilin.repogrowth.github.model.GitHubRepositoryResponse;
import com.kholodilin.repogrowth.github.model.GitHubSearchItem;
import com.kholodilin.repogrowth.repository.domain.GitHubOwner;
import com.kholodilin.repogrowth.repository.domain.OwnerType;
import com.kholodilin.repogrowth.repository.domain.Repository;
import com.kholodilin.repogrowth.repository.persistence.GitHubOwnerJdbcRepository;
import com.kholodilin.repogrowth.repository.persistence.RepositoryJdbcRepository;
import com.kholodilin.repogrowth.search.domain.ActivityStatus;
import com.kholodilin.repogrowth.search.domain.SearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RepositoryEnricherTest {

    @Mock
    GitHubClient gitHubClient;
    @Mock
    GitHubOwnerJdbcRepository ownerRepository;
    @Mock
    RepositoryJdbcRepository repositoryJdbcRepository;
    @Mock
    ActivityClassifier activityClassifier;

    private final Instant now = Instant.parse("2026-09-23T06:00:00Z");
    private RepositoryEnricher enricher;

    @BeforeEach
    void setUp() {
        SearchProperties properties = new SearchProperties(0, 50, Duration.ofHours(24), 30, new SearchProperties.Activity(30, 180));
        enricher = new RepositoryEnricher(
                gitHubClient,
                ownerRepository,
                repositoryJdbcRepository,
                activityClassifier,
                properties,
                Clock.fixed(now, ZoneOffset.UTC)
        );
    }

    @Test
    void fromSearchItemUsesFallbackOwnerAndUrl() {
        when(activityClassifier.classify(any())).thenReturn(ActivityStatus.ACTIVE);
        GitHubSearchItem item = new GitHubSearchItem(
                9L, "acme/demo", null, 3, 1, "Java", "desc", null,
                now, now, now
        );
        SearchResult result = enricher.fromSearchItem(4L, 2, item);
        assertThat(result.owner()).isEmpty();
        assertThat(result.htmlUrl()).isEqualTo("https://github.com/acme/demo");
        assertThat(result.activityStatus()).isEqualTo(ActivityStatus.ACTIVE);
        assertThat(result.watchers()).isZero();
    }

    @Test
    void enrichSkipsGitHubWhenTheCacheIsFresh() {
        Repository cached = repository(now.minus(Duration.ofHours(1)));
        when(repositoryJdbcRepository.findByGithubId(9L)).thenReturn(Optional.of(cached));
        when(activityClassifier.classify(eq(false), eq(cached.activityAt()))).thenReturn(ActivityStatus.LOW_ACTIVITY);
        SearchResult snapshot = snapshot();
        SearchResult enriched = enricher.enrich(snapshot, item());
        assertThat(enriched.stars()).isEqualTo(21);
        assertThat(enriched.activityStatus()).isEqualTo(ActivityStatus.LOW_ACTIVITY);
        assertThat(enriched.language()).isEqualTo("Java");
    }

    @Test
    void enrichFallsBackWhenContributorAndCommitLookupsFail() {
        when(repositoryJdbcRepository.findByGithubId(9L)).thenReturn(Optional.empty());
        when(gitHubClient.getRepository("acme", "demo")).thenReturn(remote(null));
        when(gitHubClient.countContributors("acme", "demo")).thenThrow(GitHubException.notFound("missing"));
        when(gitHubClient.latestCommitAt("acme", "demo")).thenThrow(GitHubException.notFound("missing"));
        GitHubOwner owner = new GitHubOwner(3L, 9L, "acme", OwnerType.USER, null, null, now, now);
        when(ownerRepository.upsert(eq(1L), eq("acme"), eq(OwnerType.USER), eq(null), eq(null))).thenReturn(owner);
        Repository stored = repository(now);
        when(repositoryJdbcRepository.upsertKeepingTracking(any())).thenReturn(stored);
        when(activityClassifier.classify(eq(false), any())).thenReturn(ActivityStatus.UNKNOWN);

        SearchResult enriched = enricher.enrich(snapshot(), item());
        assertThat(enriched.fullName()).isEqualTo("acme/demo");
        verify(repositoryJdbcRepository).replaceTopics(eq(13L), eq(List.of("kafka")));
    }

    @Test
    void enrichStoresAnOrganizationOwnerAndKeepsAPreviousCommit() {
        Repository cached = repository(now.minus(Duration.ofDays(3)));
        when(repositoryJdbcRepository.findByGithubId(9L)).thenReturn(Optional.of(cached));
        GitHubOwnerResponse org = new GitHubOwnerResponse(8L, "acme-org", "Organization", "https://img", "https://github.com/acme-org");
        when(gitHubClient.getRepository("acme", "demo")).thenReturn(remote(org));
        when(gitHubClient.countContributors("acme", "demo")).thenReturn(6);
        when(gitHubClient.latestCommitAt("acme", "demo")).thenReturn(Optional.of(now));
        GitHubOwner owner = new GitHubOwner(4L, 8L, "acme-org", OwnerType.ORGANIZATION, "https://img", "https://github.com/acme-org", now, now);
        when(ownerRepository.upsert(8L, "acme-org", OwnerType.ORGANIZATION, "https://img", "https://github.com/acme-org")).thenReturn(owner);
        when(repositoryJdbcRepository.upsertKeepingTracking(any())).thenReturn(repository(now));
        when(activityClassifier.classify(eq(false), eq(now))).thenReturn(ActivityStatus.ACTIVE);

        assertThat(enricher.enrich(snapshot(), item()).contributors()).isEqualTo(7);
        assertThat(enricher.fresh(null)).isFalse();
        assertThat(enricher.fresh(now.minus(Duration.ofHours(1)))).isTrue();
        assertThat(enricher.fresh(now.minus(Duration.ofDays(3)))).isFalse();
    }

    private SearchResult snapshot() {
        return new SearchResult(
                1L, 4L, 2, 9L, "acme/demo", "acme", 1, 0, 1, 0, null, null,
                "https://github.com/acme/demo", now, now, now, ActivityStatus.UNKNOWN, null
        );
    }

    private GitHubSearchItem item() {
        return new GitHubSearchItem(
                9L, "acme/demo", new GitHubOwnerResponse(1L, "acme", "User", null, null),
                3, 1, "Java", "desc", "https://github.com/acme/demo", now, now, now
        );
    }

    private GitHubRepositoryResponse remote(GitHubOwnerResponse owner) {
        return new GitHubRepositoryResponse(
                9L, "demo", "acme/demo", "remote", false, "public", "main", "Java",
                false, false, 21, 2, 4, 1, "https://github.com/acme/demo",
                now, now, now, owner, List.of("kafka"), "https://example.com",
                new GitHubLicenseResponse("mit", "MIT", "MIT")
        );
    }

    private Repository repository(Instant enrichedAt) {
        return new Repository(
                13L, 9L, 3L, "demo", "acme/demo", "cached", "PUBLIC", "main", "Java",
                false, false, 21, 2, 4, 1, 7, false, now, now, now, now, null, enrichedAt, now, now
        );
    }
}
