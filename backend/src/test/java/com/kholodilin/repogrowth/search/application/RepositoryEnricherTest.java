package com.kholodilin.repogrowth.search.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.kholodilin.repogrowth.github.model.GitHubOwnerResponse;
import com.kholodilin.repogrowth.github.model.GitHubSearchItem;
import com.kholodilin.repogrowth.repository.domain.Repository;
import com.kholodilin.repogrowth.repository.persistence.RepositoryJdbcRepository;
import com.kholodilin.repogrowth.search.domain.ActivityStatus;
import com.kholodilin.repogrowth.search.domain.SearchResult;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RepositoryEnricherTest {

  @Mock RepositoryJdbcRepository repositoryJdbcRepository;
  @Mock ActivityClassifier activityClassifier;

  private final Instant now = Instant.parse("2026-09-23T06:00:00Z");
  private RepositoryEnricher enricher;

  @BeforeEach
  void setUp() {
    enricher = new RepositoryEnricher(repositoryJdbcRepository, activityClassifier);
  }

  @Test
  void fromSearchItemUsesFallbackOwnerAndUrl() {
    when(activityClassifier.classify(any())).thenReturn(ActivityStatus.ACTIVE);
    when(repositoryJdbcRepository.findByGithubId(9L)).thenReturn(Optional.empty());
    GitHubSearchItem item =
        new GitHubSearchItem(9L, "acme/demo", null, 3, 1, "Java", "desc", null, now, now, now);
    SearchResult result = enricher.fromSearchItem(4L, 2, item);
    assertThat(result.owner()).isEmpty();
    assertThat(result.htmlUrl()).isEqualTo("https://github.com/acme/demo");
    assertThat(result.activityStatus()).isEqualTo(ActivityStatus.ACTIVE);
    assertThat(result.watchers()).isZero();
  }

  @Test
  void fromSearchItemPrefersTheEnrichedRepositoryCache() {
    Repository cached = repository(now.minus(Duration.ofHours(1)));
    when(activityClassifier.classify(any())).thenReturn(ActivityStatus.ACTIVE);
    when(repositoryJdbcRepository.findByGithubId(9L)).thenReturn(Optional.of(cached));
    when(activityClassifier.classify(eq(false), eq(cached.activityAt())))
        .thenReturn(ActivityStatus.LOW_ACTIVITY);

    SearchResult result = enricher.fromSearchItem(4L, 2, item());

    assertThat(result.fullName()).isEqualTo("acme/demo");
    assertThat(result.owner()).isEqualTo("acme");
    assertThat(result.stars()).isEqualTo(21);
    assertThat(result.watchers()).isEqualTo(2);
    assertThat(result.forks()).isEqualTo(4);
    assertThat(result.contributors()).isEqualTo(7);
    assertThat(result.description()).isEqualTo("cached");
    assertThat(result.activityStatus()).isEqualTo(ActivityStatus.LOW_ACTIVITY);
    assertThat(result.metadataUpdatedAt()).isEqualTo(cached.enrichedAt());
  }

  @Test
  void fromSearchItemIgnoresACachedRepositoryThatWasNeverEnriched() {
    when(activityClassifier.classify(any())).thenReturn(ActivityStatus.ACTIVE);
    when(repositoryJdbcRepository.findByGithubId(9L)).thenReturn(Optional.of(repository(null)));

    SearchResult result = enricher.fromSearchItem(4L, 2, item());

    assertThat(result.stars()).isEqualTo(3);
    assertThat(result.contributors()).isZero();
    assertThat(result.description()).isEqualTo("desc");
    assertThat(result.metadataUpdatedAt()).isNull();
  }

  @Test
  void fromSearchItemKeepsSearchMetadataWhenTheEnrichedCacheLacksIt() {
    Instant searchCreatedAt = Instant.parse("2020-01-01T00:00:00Z");
    Instant searchUpdatedAt = Instant.parse("2021-01-01T00:00:00Z");
    Instant searchPushedAt = Instant.parse("2022-01-01T00:00:00Z");
    GitHubSearchItem item =
        new GitHubSearchItem(
            9L,
            "acme/demo",
            new GitHubOwnerResponse(1L, "acme", "User", null, null),
            3,
            1,
            "Kotlin",
            "from search",
            "https://github.com/acme/demo",
            searchCreatedAt,
            searchUpdatedAt,
            searchPushedAt);
    Repository sparse =
        new Repository(
            13L,
            9L,
            3L,
            "demo",
            "acme/demo",
            null,
            "PUBLIC",
            "main",
            null,
            false,
            false,
            21,
            2,
            4,
            1,
            7,
            false,
            null,
            null,
            null,
            null,
            null,
            now,
            now,
            now);
    when(activityClassifier.classify(any())).thenReturn(ActivityStatus.ACTIVE);
    when(repositoryJdbcRepository.findByGithubId(9L)).thenReturn(Optional.of(sparse));
    when(activityClassifier.classify(eq(false), eq(searchPushedAt)))
        .thenReturn(ActivityStatus.LOW_ACTIVITY);

    SearchResult result = enricher.fromSearchItem(4L, 2, item);

    assertThat(result.language()).isEqualTo("Kotlin");
    assertThat(result.description()).isEqualTo("from search");
    assertThat(result.repositoryCreatedAt()).isEqualTo(searchCreatedAt);
    assertThat(result.repositoryUpdatedAt()).isEqualTo(searchUpdatedAt);
    assertThat(result.activityAt()).isEqualTo(searchPushedAt);
    assertThat(result.activityStatus()).isEqualTo(ActivityStatus.LOW_ACTIVITY);
  }

  private GitHubSearchItem item() {
    return new GitHubSearchItem(
        9L,
        "acme/demo",
        new GitHubOwnerResponse(1L, "acme", "User", null, null),
        3,
        1,
        "Java",
        "desc",
        "https://github.com/acme/demo",
        now,
        now,
        now);
  }

  private Repository repository(Instant enrichedAt) {
    return new Repository(
        13L,
        9L,
        3L,
        "demo",
        "acme/demo",
        "cached",
        "PUBLIC",
        "main",
        "Java",
        false,
        false,
        21,
        2,
        4,
        1,
        7,
        false,
        now,
        now,
        now,
        now,
        null,
        enrichedAt,
        now,
        now);
  }
}
