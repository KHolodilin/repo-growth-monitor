package com.kholodilin.collector.integration.github;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.kholodilin.collector.contracts.CollectorProtocol.*;
import com.kholodilin.collector.model.ExecutionSettings;
import com.kholodilin.collector.model.github.*;
import java.net.URI;
import java.time.*;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class AssignedGithubClientTest {
  JsonMapper json = JsonMapper.builder().build();
  ExecutionSettings settings =
      new ExecutionSettings(
          URI.create("https://api.github.com"),
          Duration.ofMinutes(1),
          Duration.ofMillis(250),
          Duration.ofSeconds(10),
          5,
          5,
          5 * 1024 * 1024);
  GitHubSourceReader source = mock(GitHubSourceReader.class);
  AssignedGithubClient client = new AssignedGithubClient(source, settings, json);
  JobRequest job =
      new JobRequest(
          1,
          UUID.randomUUID(),
          UUID.randomUUID(),
          UUID.randomUUID(),
          TaskType.TRAFFIC,
          new RepositoryTarget("202", "owner", "repo"),
          LocalDate.now(),
          1,
          Parameters.repository(),
          "results",
          Instant.now().plusSeconds(600));

  @BeforeEach
  void resetSource() {
    reset(source);
  }

  @Test
  void repositoryMapsGitHubFieldsToTypedObservation() {
    when(source.fetch(
            eq("rgm"), eq(job), eq("repository"), eq("GET"), anyString(), isNull(), eq(false)))
        .thenReturn(
            json.readTree(
                "{\"stargazers_count\":3,\"forks_count\":1,\"subscribers_count\":2,\"id\":202}"));

    RepositoryObservation remote = client.repository("rgm", job);

    assertThat(remote.stars()).isEqualTo(3);
    assertThat(remote.forks()).isEqualTo(1);
    assertThat(remote.watchers()).isEqualTo(2);
    assertThat(remote.rawJson()).contains("stargazers_count");
  }

  @Test
  void trafficSeriesParsesUtcTimestamps() {
    when(source.fetch(eq("rgm"), eq(job), eq("views"), eq("GET"), anyString(), isNull(), eq(false)))
        .thenReturn(
            json.readTree(
                "{\"views\":[{\"timestamp\":\"2026-11-01T00:00:00Z\",\"count\":0,\"uniques\":4}]}"));

    TrafficSeries series = client.views("rgm", job);

    assertThat(series.buckets()).hasSize(1);
    assertThat(series.buckets().getFirst().date()).isEqualTo(LocalDate.of(2026, 11, 1));
    assertThat(series.buckets().getFirst().count()).isZero();
    assertThat(series.buckets().getFirst().uniques()).isEqualTo(4);
  }

  @Test
  void searchObservationTracksPositionAndPublicScope() {
    JobRequest search =
        new JobRequest(
            1,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            TaskType.SEARCH_QUERY,
            new RepositoryTarget("202", "owner", "repo"),
            LocalDate.now(),
            1,
            new Parameters("spring", null, null, 50, null, null, true),
            "results",
            Instant.now().plusSeconds(600));
    when(source.fetch(
            eq("rgm"), eq(search), eq("search"), eq("GET"), anyString(), isNull(), eq(false)))
        .thenReturn(
            json.readTree(
                "{\"total_count\":1,\"incomplete_results\":false,\"items\":[{\"id\":202,\"private\":false,\"full_name\":\"owner/repo\",\"stargazers_count\":1,\"forks_count\":0}]}"));

    SearchObservation data = client.search("rgm", search, "search");

    assertThat(data.totalCount()).isEqualTo(1);
    assertThat(data.trackedPosition()).isEqualTo(1);
    assertThat(data.allPublic()).isTrue();
    assertThat(data.items().getFirst().fullName()).isEqualTo("owner/repo");
  }

  @Test
  void contributorPageReportsContinuationFromFullPage() {
    StringBuilder page = new StringBuilder("[");
    for (int i = 0; i < 100; i++) {
      if (i > 0) page.append(',');
      page.append("{\"login\":\"u").append(i).append("\"}");
    }
    page.append(']');
    when(source.fetch(
            eq("rgm"), eq(job), eq("contributors:1"), eq("GET"), anyString(), isNull(), eq(false)))
        .thenReturn(json.readTree(page.toString()));

    ContributorPage slice = client.contributorsPage("rgm", job, 1);

    assertThat(slice.items()).hasSize(100);
    assertThat(slice.hasNext()).isTrue();
  }

  @Test
  void contributorPageDoesNotClaimContinuationOnShortPage() {
    when(source.fetch(
            eq("rgm"), eq(job), eq("contributors:1"), eq("GET"), anyString(), isNull(), eq(false)))
        .thenReturn(json.readTree("[{\"login\":\"a\"},{\"login\":\"b\"}]"));

    ContributorPage slice = client.contributorsPage("rgm", job, 1);

    assertThat(slice.items()).hasSize(2);
    assertThat(slice.hasNext()).isFalse();
  }

  @Test
  void repositoryArrayPageReportsContinuationFromFullPageOnly() {
    StringBuilder full = new StringBuilder("[");
    for (int i = 0; i < 100; i++) {
      if (i > 0) full.append(',');
      full.append("{\"id\":").append(i).append(",\"owner\":{\"login\":\"owner\"}}");
    }
    full.append(']');
    when(source.fetch(
            eq("rgm"),
            eq(job),
            eq("accessible-repositories:1"),
            eq("GET"),
            anyString(),
            isNull(),
            eq(false)))
        .thenReturn(json.readTree(full.toString()));
    assertThat(client.accessibleRepositoriesPage("rgm", job, 1).hasNext()).isTrue();

    when(source.fetch(
            eq("rgm"),
            eq(job),
            eq("accessible-repositories:2"),
            eq("GET"),
            anyString(),
            isNull(),
            eq(false)))
        .thenReturn(json.readTree("[{\"id\":1,\"owner\":{\"login\":\"owner\"}}]"));
    assertThat(client.accessibleRepositoriesPage("rgm", job, 2).hasNext()).isFalse();
  }
}
