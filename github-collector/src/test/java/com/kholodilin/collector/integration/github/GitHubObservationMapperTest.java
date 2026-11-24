package com.kholodilin.collector.integration.github;

import static org.assertj.core.api.Assertions.*;

import com.kholodilin.collector.model.IncompleteObservationException;
import com.kholodilin.collector.model.github.*;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.MissingNode;

class GitHubObservationMapperTest {
  JsonMapper json = JsonMapper.builder().build();
  GitHubObservationMapper mapper = new GitHubObservationMapper(json);

  @Test
  void repositoryMapsRequiredCountsAndOpaqueMetadata() {
    JsonNode remote =
        json.readTree(
            "{\"stargazers_count\":3,\"forks_count\":1,\"subscribers_count\":2,\"id\":202,\"extra\":\"kept\"}");

    RepositoryObservation observation = mapper.repository(remote);

    assertThat(observation.stars()).isEqualTo(3);
    assertThat(observation.forks()).isEqualTo(1);
    assertThat(observation.watchers()).isEqualTo(2);
    assertThat(observation.rawJson()).contains("\"extra\":\"kept\"");
  }

  @Test
  void repositoryAllowsMissingWatchersButRejectsMissingStars() {
    assertThat(
            mapper
                .repository(json.readTree("{\"stargazers_count\":1,\"forks_count\":0}"))
                .watchers())
        .isNull();
    assertThatThrownBy(
            () -> mapper.repository(json.readTree("{\"forks_count\":0,\"subscribers_count\":1}")))
        .isInstanceOf(IncompleteObservationException.class);
  }

  @Test
  void repositoryRejectsNullNegativeNonIntegralAndOutOfLongCounts() {
    assertThatThrownBy(() ->
            mapper.repository(
                json.readTree(
                    "{\"stargazers_count\":null,\"forks_count\":0,\"subscribers_count\":0}")))
        .isInstanceOf(IncompleteObservationException.class);
    assertThatThrownBy(() ->
            mapper.repository(
                json.readTree(
                    "{\"stargazers_count\":-1,\"forks_count\":0,\"subscribers_count\":0}")))
        .isInstanceOf(IncompleteObservationException.class);
    assertThatThrownBy(() ->
            mapper.repository(
                json.readTree(
                    "{\"stargazers_count\":1.5,\"forks_count\":0,\"subscribers_count\":0}")))
        .isInstanceOf(IncompleteObservationException.class);
    assertThatThrownBy(() ->
            mapper.repository(
                json.readTree(
                    "{\"stargazers_count\":9223372036854775808,\"forks_count\":0,\"subscribers_count\":0}")))
        .isInstanceOf(IncompleteObservationException.class);
  }

  @Test
  void contributorsPreserveAnonymousLoginAndEmptyArray() {
    ContributorPage empty = mapper.contributors(json.readTree("[]"), 100);
    assertThat(empty.items()).isEmpty();
    assertThat(empty.hasNext()).isFalse();
    assertThat(empty.rawJson()).isEqualTo("[]");

    ContributorPage page =
        mapper.contributors(json.readTree("[{\"login\":\"a\"},{\"login\":\"  \"},{}]"), 100);
    assertThat(page.items())
        .extracting(ContributorPage.ContributorEntry::login)
        .containsExactly("a", null, null);
    assertThat(page.hasNext()).isFalse();
  }

  @Test
  void contributorsHasNextUsesRequestedPageSizeNotHardcodedHundred() {
    assertThat(
            mapper
                .contributors(json.readTree("[{\"login\":\"a\"},{\"login\":\"b\"}]"), 2)
                .hasNext())
        .isTrue();
    assertThat(mapper.contributors(json.readTree("[{\"login\":\"a\"}]"), 2).hasNext()).isFalse();
    assertThatThrownBy(() -> mapper.contributors(json.readTree("[]"), 0))
        .isInstanceOf(IncompleteObservationException.class);
    assertThatThrownBy(() -> mapper.contributors(json.readTree("[]"), -1))
        .isInstanceOf(IncompleteObservationException.class);
    assertThatThrownBy(() -> mapper.contributors(json.readTree("{\"login\":\"a\"}"), 10))
        .isInstanceOf(IncompleteObservationException.class);
    assertThatThrownBy(() -> mapper.contributors(json.readTree("[{\"login\":1}]"), 10))
        .isInstanceOf(IncompleteObservationException.class);
  }

  @Test
  void contributorTotalRequiresNonNegativeIntegralTotal() {
    assertThat(mapper.contributorTotal(json.readTree("{\"total\":0}")).total()).isZero();
    assertThatThrownBy(() -> mapper.contributorTotal(json.readTree("{}")))
        .isInstanceOf(IncompleteObservationException.class);
    assertThatThrownBy(() -> mapper.contributorTotal(json.readTree("{\"total\":-2}")))
        .isInstanceOf(IncompleteObservationException.class);
  }

  @Test
  void optionalDocumentDistinguishesAbsentNullAndNamedPayload() {
    assertThat(mapper.optionalDocument(null).present()).isFalse();
    assertThat(mapper.optionalDocument(json.readTree("null")).present()).isFalse();
    assertThat(mapper.optionalDocument(MissingNode.getInstance()).present()).isFalse();

    OpaqueDocument named =
        mapper.optionalDocument(json.readTree("{\"name\":\"README.md\",\"size\":1}"));
    assertThat(named.present()).isTrue();
    assertThat(named.name()).isEqualTo("README.md");
    assertThat(named.rawJson()).contains("README.md");

    OpaqueDocument unnamed = mapper.optionalDocument(json.readTree("{\"content\":\"x\"}"));
    assertThat(unnamed.present()).isTrue();
    assertThat(unnamed.name()).isNull();
  }

  @Test
  void socialPreviewUnusedOnErrorsMissingRepositoryOrNull() {
    assertThat(mapper.socialPreview(null).usable()).isFalse();
    assertThat(mapper.socialPreview(json.readTree("null")).usable()).isFalse();
    assertThat(mapper.socialPreview(json.readTree("{\"errors\":[{\"message\":\"x\"}]}")).usable())
        .isFalse();
    assertThat(mapper.socialPreview(json.readTree("{\"data\":{\"repository\":null}}")).usable())
        .isFalse();
    assertThat(mapper.socialPreview(json.readTree("{\"data\":{}}")).usable()).isFalse();

    SocialPreview usable =
        mapper.socialPreview(
            json.readTree(
                "{\"data\":{\"repository\":{\"usesCustomOpenGraphImage\":true,\"openGraphImageUrl\":\"https://x\"}}}"));
    assertThat(usable.usable()).isTrue();
    assertThat(usable.repositoryJson()).contains("openGraphImageUrl");
  }

  @Test
  void viewsParsesUtcBucketsIncludingEmptyArrayAndIgnoresUnknownFields() {
    TrafficSeries series =
        mapper.views(
            json.readTree(
                "{\"count\":9,\"uniques\":3,\"views\":[{\"timestamp\":\"2026-11-01T00:00:00Z\",\"count\":0,\"uniques\":4,\"extra\":1}],\"ignored\":true}"));

    assertThat(series.buckets()).hasSize(1);
    assertThat(series.buckets().getFirst().date()).isEqualTo(LocalDate.of(2026, 11, 1));
    assertThat(series.buckets().getFirst().count()).isZero();
    assertThat(series.buckets().getFirst().uniques()).isEqualTo(4);
    assertThat(mapper.views(json.readTree("{\"views\":[]}")).buckets()).isEmpty();
    assertThat(mapper.clones(json.readTree("{\"clones\":[]}")).buckets()).isEmpty();
  }

  @Test
  void trafficRejectsMissingNullWrongTypeAndBadTimestamps() {
    assertThatThrownBy(() -> mapper.views(json.readTree("{}")))
        .isInstanceOf(IncompleteObservationException.class);
    assertThatThrownBy(() -> mapper.views(json.readTree("{\"views\":null}")))
        .isInstanceOf(IncompleteObservationException.class);
    assertThatThrownBy(() -> mapper.views(json.readTree("{\"views\":{}}")))
        .isInstanceOf(IncompleteObservationException.class);
    assertThatThrownBy(() -> mapper.views(json.readTree("[]")))
        .isInstanceOf(IncompleteObservationException.class);
    assertThatThrownBy(() ->
            mapper.views(
                json.readTree(
                    "{\"views\":[{\"timestamp\":\"2026-11-01T00:00:00Z\",\"uniques\":1}]}")))
        .isInstanceOf(IncompleteObservationException.class);
    assertThatThrownBy(() ->
            mapper.views(
                json.readTree(
                    "{\"views\":[{\"timestamp\":\"2026-11-01T00:00:00Z\",\"count\":-1,\"uniques\":1}]}")))
        .isInstanceOf(IncompleteObservationException.class);
    assertThatThrownBy(() ->
            mapper.views(
                json.readTree(
                    "{\"views\":[{\"timestamp\":\"not-a-timestamp\",\"count\":1,\"uniques\":1}]}")))
        .isInstanceOf(IncompleteObservationException.class);
    assertThatThrownBy(() ->
            mapper.views(
                json.readTree("{\"views\":[{\"timestamp\":1,\"count\":1,\"uniques\":1}]}")))
        .isInstanceOf(IncompleteObservationException.class);
  }

  @Test
  void referrersAndPathsMapKeyTitleAndRejectNonArray() {
    PopularSources sources =
        mapper.referrers(
            json.readTree(
                "[{\"referrer\":\"github.com\",\"title\":\"GitHub\",\"count\":2,\"uniques\":1},{\"referrer\":\"x\",\"count\":0,\"uniques\":0}]"));

    assertThat(sources.entries()).hasSize(2);
    assertThat(sources.entries().getFirst().key()).isEqualTo("github.com");
    assertThat(sources.entries().getFirst().title()).isEqualTo("GitHub");
    assertThat(sources.entries().get(1).title()).isNull();
    assertThat(sources.entries().get(1).count()).isZero();

    assertThat(mapper.popularPaths(json.readTree("[]")).entries()).isEmpty();
    assertThatThrownBy(() -> mapper.popularPaths(json.readTree("{\"path\":\"/\"}")))
        .isInstanceOf(IncompleteObservationException.class);
    assertThatThrownBy(() ->
            mapper.referrers(
                json.readTree("[{\"referrer\":\"x\",\"title\":1,\"count\":1,\"uniques\":0}]")))
        .isInstanceOf(IncompleteObservationException.class);
  }

  @Test
  void searchTracksPositionPublicScopeIncompleteAndMetadata() {
    SearchObservation data =
        mapper.search(
            json.readTree(
                """
                {
                  "total_count": 2,
                  "incomplete_results": true,
                  "items": [
                    {"id": 101, "private": false, "full_name": "a/b", "stargazers_count": 1, "forks_count": 0, "extra": true},
                    {"id": "202", "private": false, "full_name": "owner/repo", "stargazers_count": 5, "forks_count": 1}
                  ],
                  "unused": "ok"
                }
                """),
            "202");

    assertThat(data.totalCount()).isEqualTo(2);
    assertThat(data.incompleteResults()).isTrue();
    assertThat(data.trackedPosition()).isEqualTo(2);
    assertThat(data.allPublic()).isTrue();
    assertThat(data.items().get(1).fullName()).isEqualTo("owner/repo");
    assertThat(data.rawJson()).contains("\"unused\":\"ok\"");
  }

  @Test
  void searchTreatsMissingIncompleteAsFalseAndUnknownPrivateNeverConfirmsPublic() {
    SearchObservation missingIncomplete =
        mapper.search(
            json.readTree(
                "{\"total_count\":1,\"items\":[{\"id\":1,\"private\":false,\"full_name\":\"a/b\",\"stargazers_count\":0,\"forks_count\":0}]}"),
            "9");
    assertThat(missingIncomplete.incompleteResults()).isFalse();
    assertThat(missingIncomplete.trackedPosition()).isNull();

    SearchObservation privateHit =
        mapper.search(
            json.readTree(
                "{\"total_count\":1,\"incomplete_results\":false,\"items\":[{\"id\":1,\"private\":true,\"full_name\":\"a/b\",\"stargazers_count\":0,\"forks_count\":0}]}"),
            "1");
    assertThat(privateHit.allPublic()).isFalse();
    assertThat(privateHit.items().getFirst().isPrivate()).isTrue();

    SearchObservation missingPrivate =
        mapper.search(
            json.readTree(
                "{\"total_count\":1,\"items\":[{\"id\":1,\"full_name\":\"a/b\",\"stargazers_count\":0,\"forks_count\":0}]}"),
            "1");
    assertThat(missingPrivate.allPublic()).isFalse();
    assertThat(missingPrivate.items().getFirst().isPrivate()).isTrue();

    SearchObservation invalidPrivate =
        mapper.search(
            json.readTree(
                "{\"total_count\":1,\"items\":[{\"id\":1,\"private\":\"no\",\"full_name\":\"a/b\",\"stargazers_count\":0,\"forks_count\":0}]}"),
            "1");
    assertThat(invalidPrivate.allPublic()).isFalse();
    assertThat(invalidPrivate.items().getFirst().isPrivate()).isTrue();
  }

  @Test
  void searchRejectsCorruptRequiredFields() {
    assertThatThrownBy(
            () -> mapper.search(json.readTree("{\"incomplete_results\":false,\"items\":[]}"), "1"))
        .isInstanceOf(IncompleteObservationException.class);
    assertThatThrownBy(() ->
            mapper.search(
                json.readTree("{\"total_count\":1,\"incomplete_results\":null,\"items\":[]}"), "1"))
        .isInstanceOf(IncompleteObservationException.class);
    assertThatThrownBy(() ->
            mapper.search(
                json.readTree("{\"total_count\":1,\"incomplete_results\":\"yes\",\"items\":[]}"),
                "1"))
        .isInstanceOf(IncompleteObservationException.class);
    assertThatThrownBy(() -> mapper.search(json.readTree("{\"total_count\":1}"), "1"))
        .isInstanceOf(IncompleteObservationException.class);
    assertThatThrownBy(() ->
            mapper.search(
                json.readTree(
                    "{\"total_count\":1,\"items\":[{\"id\":1,\"full_name\":\"\",\"stargazers_count\":0,\"forks_count\":0}]}"),
                "1"))
        .isInstanceOf(IncompleteObservationException.class);
    assertThatThrownBy(() ->
            mapper.search(
                json.readTree(
                    "{\"total_count\":1,\"items\":[{\"full_name\":\"a/b\",\"stargazers_count\":0,\"forks_count\":0}]}"),
                "1"))
        .isInstanceOf(IncompleteObservationException.class);
  }

  @Test
  void arrayPageUsesRequestedPageSizeAndMergePreservesOrder() {
    JsonArrayPage page =
        mapper.arrayPage(json.readTree("[{\"id\":1},{\"id\":2,\"extra\":true}]"), 10);
    assertThat(page.size()).isEqualTo(2);
    assertThat(page.hasNext()).isFalse();
    assertThat(page.rawJson()).contains("\"extra\":true");
    assertThat(mapper.arrayPage(json.readTree("[{\"id\":1},{\"id\":2}]"), 2).hasNext()).isTrue();
    assertThatThrownBy(() -> mapper.arrayPage(json.readTree("[]"), 0))
        .isInstanceOf(IncompleteObservationException.class);

    assertThat(mapper.mergeJsonArrays(null)).isEqualTo("[]");
    assertThat(mapper.mergeJsonArrays(List.of())).isEqualTo("[]");
    assertThat(mapper.mergeJsonArrays(List.of("[{\"id\":1}]", "[{\"id\":2}]")))
        .isEqualTo("[{\"id\":1},{\"id\":2}]");
    assertThatThrownBy(() -> mapper.mergeJsonArrays(List.of("{\"id\":1}")))
        .isInstanceOf(IncompleteObservationException.class);
    assertThatThrownBy(() -> mapper.arrayPage(json.readTree("{}"), 10))
        .isInstanceOf(IncompleteObservationException.class);
  }

  @Test
  void ownedRepositoriesMetadataFiltersByOwnerCaseInsensitively() {
    JsonArrayPage page =
        mapper.arrayPage(
            json.readTree(
                "[{\"id\":1,\"owner\":{\"login\":\"Owner\"}},{\"id\":2,\"owner\":{\"login\":\"other\"}}]"),
            100);

    assertThat(mapper.ownedRepositoriesMetadata(page, "owner"))
        .isEqualTo("[{\"id\":1,\"owner\":{\"login\":\"Owner\"}}]");
  }

  @Test
  void accessibleRepositoriesMetadataKeepsEveryOwnerAndRejectsNonObjects() {
    JsonArrayPage page =
        mapper.arrayPage(
            json.readTree(
                "[{\"id\":1,\"owner\":{\"login\":\"Owner\"}},{\"id\":2,\"owner\":{\"login\":\"other\"}}]"),
            100);

    assertThat(mapper.accessibleRepositoriesMetadata(page))
        .isEqualTo(
            "[{\"id\":1,\"owner\":{\"login\":\"Owner\"}},{\"id\":2,\"owner\":{\"login\":\"other\"}}]");
    assertThatThrownBy(() ->
            mapper.accessibleRepositoriesMetadata(mapper.arrayPage(json.readTree("[1]"), 100)))
        .isInstanceOf(IncompleteObservationException.class);
  }

  @Test
  void contentPresentAcceptsSourceMarkerAndRejectsMissingOrUnexpectedShapes() {
    assertThat(mapper.contentPresent(null)).isFalse();
    assertThat(mapper.contentPresent(json.readTree("null"))).isFalse();
    assertThat(mapper.contentPresent(MissingNode.getInstance())).isFalse();
    assertThat(mapper.contentPresent(json.readTree("{\"exists\":true}"))).isTrue();

    assertThatThrownBy(() -> mapper.contentPresent(json.readTree("{\"name\":\"SECURITY.md\"}")))
        .isInstanceOf(IncompleteObservationException.class);
    assertThatThrownBy(() -> mapper.contentPresent(json.readTree("{\"exists\":false}")))
        .isInstanceOf(IncompleteObservationException.class);
    assertThatThrownBy(() -> mapper.contentPresent(json.readTree("[]")))
        .isInstanceOf(IncompleteObservationException.class);
  }
}
