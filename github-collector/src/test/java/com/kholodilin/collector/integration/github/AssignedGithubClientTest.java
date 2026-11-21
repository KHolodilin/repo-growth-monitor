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
import org.mockito.ArgumentCaptor;
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
  GitHubObservationMapper observations = new GitHubObservationMapper(json);
  AssignedGithubClient client = new AssignedGithubClient(source, settings, observations, json);
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
  void repositoryBuildsRepoPathAndReturnsTypedObservation() {
    when(source.fetch(
            eq("rgm"), eq(job), eq("repository"), eq("GET"), anyString(), isNull(), eq(false)))
        .thenReturn(
            json.readTree(
                "{\"stargazers_count\":3,\"forks_count\":1,\"subscribers_count\":2,\"id\":202}"));

    RepositoryObservation remote = client.repository("rgm", job);

    ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
    verify(source)
        .fetch(
            eq("rgm"), eq(job), eq("repository"), eq("GET"), path.capture(), isNull(), eq(false));
    assertThat(path.getValue()).isEqualTo("/repos/owner/repo");
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
  void searchObservationBuildsQueryAndTracksPosition() {
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

    ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
    verify(source)
        .fetch(eq("rgm"), eq(search), eq("search"), eq("GET"), path.capture(), isNull(), eq(false));
    assertThat(path.getValue()).contains("/search/repositories?q=");
    assertThat(path.getValue()).contains("is%3Apublic");
    assertThat(data.totalCount()).isEqualTo(1);
    assertThat(data.trackedPosition()).isEqualTo(1);
    assertThat(data.allPublic()).isTrue();
    assertThat(data.items().getFirst().fullName()).isEqualTo("owner/repo");
  }

  @Test
  void contributorPageUsesClientPageSizeForRequestAndHasNext() {
    StringBuilder page = new StringBuilder("[");
    for (int i = 0; i < AssignedGithubClient.PAGE_SIZE; i++) {
      if (i > 0) page.append(',');
      page.append("{\"login\":\"u").append(i).append("\"}");
    }
    page.append(']');
    when(source.fetch(
            eq("rgm"), eq(job), eq("contributors:1"), eq("GET"), anyString(), isNull(), eq(false)))
        .thenReturn(json.readTree(page.toString()));

    ContributorPage slice = client.contributorsPage("rgm", job, 1);

    ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
    verify(source)
        .fetch(
            eq("rgm"),
            eq(job),
            eq("contributors:1"),
            eq("GET"),
            path.capture(),
            isNull(),
            eq(false));
    assertThat(path.getValue()).contains("per_page=" + AssignedGithubClient.PAGE_SIZE + "&page=1");
    assertThat(slice.items()).hasSize(AssignedGithubClient.PAGE_SIZE);
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
  void optionalReadmeUsesOptionalFetch() {
    when(source.fetch(eq("rgm"), eq(job), eq("readme"), eq("GET"), anyString(), isNull(), eq(true)))
        .thenReturn(json.readTree("null"));

    assertThat(client.readme("rgm", job).present()).isFalse();

    when(source.fetch(eq("rgm"), eq(job), eq("readme"), eq("GET"), anyString(), isNull(), eq(true)))
        .thenReturn(json.readTree("{\"name\":\"README.md\"}"));
    OpaqueDocument present = client.readme("rgm", job);
    assertThat(present.present()).isTrue();
    assertThat(present.name()).isEqualTo("README.md");
  }

  @Test
  void socialPreviewPostsGraphqlBody() {
    when(source.fetch(
            eq("rgm"),
            eq(job),
            eq("social-preview"),
            eq("POST"),
            eq("/graphql"),
            anyString(),
            eq(true)))
        .thenReturn(
            json.readTree(
                "{\"data\":{\"repository\":{\"usesCustomOpenGraphImage\":false,\"openGraphImageUrl\":null}}}"));

    SocialPreview preview = client.socialPreview("rgm", job);

    ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
    verify(source)
        .fetch(
            eq("rgm"),
            eq(job),
            eq("social-preview"),
            eq("POST"),
            eq("/graphql"),
            body.capture(),
            eq(true));
    assertThat(body.getValue()).contains("\"owner\":\"owner\"");
    assertThat(body.getValue()).contains("\"name\":\"repo\"");
    assertThat(preview.usable()).isTrue();
  }

  @Test
  void repositoryArrayPageReportsContinuationFromFullPageOnly() {
    StringBuilder full = new StringBuilder("[");
    for (int i = 0; i < AssignedGithubClient.PAGE_SIZE; i++) {
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

  @Test
  void contentExistsUsesSourcePresenceMarker() {
    when(source.fetch(
            eq("rgm"),
            eq(job),
            eq("security:.github/SECURITY.md"),
            eq("GET"),
            anyString(),
            isNull(),
            eq(true)))
        .thenReturn(json.readTree("{\"exists\":true}"));

    assertThat(
            client.contentExists("rgm", job, "security:.github/SECURITY.md", ".github/SECURITY.md"))
        .isTrue();

    when(source.fetch(
            eq("rgm"),
            eq(job),
            eq("security:SECURITY.md"),
            eq("GET"),
            anyString(),
            isNull(),
            eq(true)))
        .thenReturn(json.readTree("null"));
    assertThat(client.contentExists("rgm", job, "security:SECURITY.md", "SECURITY.md")).isFalse();
  }
}
