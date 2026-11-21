package com.kholodilin.collector.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.kholodilin.collector.contracts.CollectorProtocol.*;
import com.kholodilin.collector.integration.github.GitHubSourceReader;
import com.kholodilin.collector.model.CollectionContext;
import com.kholodilin.collector.model.PayloadTooLarge;
import com.kholodilin.collector.repository.InMemoryObservationStore;
import com.kholodilin.collector.repository.ObservationStore;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ObservationCheckpointTest {
  JsonMapper json = JsonMapper.builder().build();
  GitHubSourceReader github = mock(GitHubSourceReader.class);
  ObservationStore store = new InMemoryObservationStore(1024);
  ObservationCheckpoint checkpoint = new ObservationCheckpoint(store, github, json);
  CollectionContext context = new CollectionContext("rgm", 7);
  JobRequest job =
      new JobRequest(
          1,
          UUID.randomUUID(),
          UUID.randomUUID(),
          UUID.randomUUID(),
          TaskType.STATS,
          new RepositoryTarget("202", "owner", "repo"),
          LocalDate.now(),
          1,
          Parameters.repository(),
          "results",
          Instant.now().plusSeconds(600));

  @BeforeEach
  void githubReturnsStars() {
    when(github.fetch(
            eq("rgm"), eq(job), eq("repository"), eq("GET"), anyString(), isNull(), eq(false)))
        .thenReturn(json.readTree("{\"stargazers_count\":0}"));
  }

  @Test
  void reusesStoredObservationWithoutCallingGitHub() {
    assertThat(
            checkpoint
                .read(context, job, "repository", "GET", "/repos/owner/repo", null, false)
                .path("stargazers_count")
                .asInt())
        .isZero();
    checkpoint.read(context, job, "repository", "GET", "/repos/owner/repo", null, false);

    verify(github, times(1))
        .fetch(eq("rgm"), eq(job), eq("repository"), eq("GET"), anyString(), isNull(), eq(false));
    assertThat(store.find(7, "repository")).isPresent();
  }

  @Test
  void persistsNewObservationFromGitHub() {
    checkpoint.read(context, job, "repository", "GET", "/repos/owner/repo", null, false);
    assertThat(store.find(7, "repository")).contains("{\"stargazers_count\":0}");
    assertThat(store.find(8, "repository")).isEmpty();
  }

  @Test
  void invalidationForcesAFreshGitHubFetch() {
    checkpoint.read(context, job, "repository", "GET", "/repos/owner/repo", null, false);
    checkpoint.invalidate(context, "repository");
    when(github.fetch(
            eq("rgm"), eq(job), eq("repository"), eq("GET"), anyString(), isNull(), eq(false)))
        .thenReturn(json.readTree("{\"stargazers_count\":3}"));

    assertThat(
            checkpoint
                .read(context, job, "repository", "GET", "/repos/owner/repo", null, false)
                .path("stargazers_count")
                .asInt())
        .isEqualTo(3);
    verify(github, times(2))
        .fetch(eq("rgm"), eq(job), eq("repository"), eq("GET"), anyString(), isNull(), eq(false));
  }

  @Test
  void firstObservedStaysStableAcrossKeysAndResetsWhenEmpty() throws Exception {
    checkpoint.read(context, job, "repository", "GET", "/repos/owner/repo", null, false);
    Instant first = checkpoint.observedAt(context);
    Thread.sleep(5);
    when(github.fetch(eq("rgm"), eq(job), eq("readme"), eq("GET"), anyString(), isNull(), eq(true)))
        .thenReturn(json.readTree("null"));
    checkpoint.read(context, job, "readme", "GET", "/repos/owner/repo/readme", null, true);

    assertThat(checkpoint.observedAt(context)).isEqualTo(first);
    checkpoint.invalidate(context, "repository");
    checkpoint.invalidate(context, "readme");
    assertThat(checkpoint.observedAt(context)).isAfter(first);
  }

  @Test
  void checkpointByteLimitIsPreserved() {
    var tight = new ObservationCheckpoint(new InMemoryObservationStore(256), github, json);
    when(github.fetch(
            anyString(), eq(job), eq("big"), anyString(), anyString(), isNull(), eq(false)))
        .thenReturn(json.readTree("\"" + "x".repeat(200) + "\""));
    tight.read(context, job, "big", "GET", "/repos/owner/repo", null, false);
    when(github.fetch(
            anyString(), eq(job), eq("more"), anyString(), anyString(), isNull(), eq(false)))
        .thenReturn(json.readTree("\"" + "y".repeat(100) + "\""));

    assertThatThrownBy(
            () -> tight.read(context, job, "more", "GET", "/repos/owner/repo", null, false))
        .isInstanceOf(PayloadTooLarge.class);
  }

  @Test
  void partialCollectionReusesEarlierCheckpointsOnRetry() {
    when(github.fetch(eq("rgm"), eq(job), eq("readme"), eq("GET"), anyString(), isNull(), eq(true)))
        .thenThrow(new RuntimeException("transient"))
        .thenReturn(json.readTree("{\"name\":\"README.md\"}"));

    checkpoint.read(context, job, "repository", "GET", "/repos/owner/repo", null, false);
    assertThatThrownBy(() ->
            checkpoint.read(context, job, "readme", "GET", "/repos/owner/repo/readme", null, true))
        .hasMessageContaining("transient");

    assertThat(
            checkpoint
                .read(context, job, "repository", "GET", "/repos/owner/repo", null, false)
                .path("stargazers_count")
                .asInt())
        .isZero();
    assertThat(
            checkpoint
                .read(context, job, "readme", "GET", "/repos/owner/repo/readme", null, true)
                .path("name")
                .asText())
        .isEqualTo("README.md");
    verify(github, times(1))
        .fetch(eq("rgm"), eq(job), eq("repository"), eq("GET"), anyString(), isNull(), eq(false));
    verify(github, times(2))
        .fetch(eq("rgm"), eq(job), eq("readme"), eq("GET"), anyString(), isNull(), eq(true));
  }
}
