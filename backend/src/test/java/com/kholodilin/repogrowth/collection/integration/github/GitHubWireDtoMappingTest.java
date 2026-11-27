package com.kholodilin.repogrowth.collection.integration.github;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class GitHubWireDtoMappingTest {

  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

  @Test
  void readmeDecodedTextHandlesUtf8Base64AndUnknownEncodings() throws Exception {
    GitHubReadmeResponse blank =
        mapper.readValue(
            """
            {"sha":"s","content":"","encoding":"base64","name":"README.md"}
            """,
            GitHubReadmeResponse.class);
    assertThat(blank.decodedText()).isEmpty();

    GitHubReadmeResponse utf8 =
        mapper.readValue(
            """
            {"sha":"s","content":"plain","encoding":"utf-8","name":"README.md"}
            """,
            GitHubReadmeResponse.class);
    assertThat(utf8.decodedText()).isEqualTo("plain");

    String encoded =
        Base64.getEncoder().encodeToString("hello readme".getBytes(StandardCharsets.UTF_8));
    GitHubReadmeResponse base64 =
        mapper.readValue(
            """
            {"sha":"s","content":"%s","encoding":"base64","name":"README.md"}
            """
                .formatted(encoded),
            GitHubReadmeResponse.class);
    assertThat(base64.decodedText()).isEqualTo("hello readme");

    GitHubReadmeResponse unknown =
        mapper.readValue(
            """
            {"sha":"s","content":"raw","encoding":"rot13","name":"README.md"}
            """,
            GitHubReadmeResponse.class);
    assertThat(unknown.decodedText()).isEqualTo("raw");
  }

  @Test
  void commitTimestampPrefersCommitterThenAuthor() throws Exception {
    assertThat(
            mapper
                .readValue(
                    """
                    {"commit":null}
                    """,
                    GitHubCommitResponse.class)
                .committedAt())
        .isNull();

    Instant author = Instant.parse("2026-01-01T00:00:00Z");
    Instant committer = Instant.parse("2026-01-02T00:00:00Z");
    assertThat(
            mapper
                .readValue(
                    """
                    {
                      "commit": {
                        "author": {"date":"%s"},
                        "committer": {"date":"%s"}
                      }
                    }
                    """
                        .formatted(author, committer),
                    GitHubCommitResponse.class)
                .committedAt())
        .isEqualTo(committer);

    assertThat(
            mapper
                .readValue(
                    """
                    {
                      "commit": {
                        "author": {"date":"%s"},
                        "committer": null
                      }
                    }
                    """
                        .formatted(author),
                    GitHubCommitResponse.class)
                .committedAt())
        .isEqualTo(author);

    assertThat(
            mapper
                .readValue(
                    """
                    {"commit":{"author":null,"committer":null}}
                    """,
                    GitHubCommitResponse.class)
                .committedAt())
        .isNull();
  }

  @Test
  void trafficSearchReleaseAndSidebarHelpersCoverNullAndPresentBranches() throws Exception {
    GitHubTrafficViewsResponse emptyViews =
        mapper.readValue(
            """
            {"count":0,"uniques":0,"views":null}
            """,
            GitHubTrafficViewsResponse.class);
    assertThat(emptyViews.days()).isEmpty();

    GitHubTrafficViewsResponse views =
        mapper.readValue(
            """
            {
              "count": 3,
              "uniques": 2,
              "views": [{"timestamp":"2026-11-01T00:00:00Z","count":3,"uniques":2}]
            }
            """,
            GitHubTrafficViewsResponse.class);
    assertThat(views.days()).hasSize(1);
    assertThat(views.days().getFirst().date()).isEqualTo(LocalDate.of(2026, 11, 1));

    GitHubTrafficClonesResponse emptyClones =
        mapper.readValue(
            """
            {"count":0,"uniques":0,"clones":null}
            """,
            GitHubTrafficClonesResponse.class);
    assertThat(emptyClones.days()).isEmpty();
    assertThat(
            mapper
                .readValue(
                    """
                    {
                      "count": 1,
                      "uniques": 1,
                      "clones": [{"timestamp":"2026-11-02T00:00:00Z","count":1,"uniques":1}]
                    }
                    """,
                    GitHubTrafficClonesResponse.class)
                .days())
        .hasSize(1);

    assertThat(
            mapper
                .readValue(
                    """
                    {"total_count":0,"items":null}
                    """,
                    GitHubSearchResponse.class)
                .itemsOrEmpty())
        .isEmpty();
    assertThat(
            mapper
                .readValue(
                    """
                    {"total_count":1,"items":[{"id":1,"full_name":"a/b","stargazers_count":1}]}
                    """,
                    GitHubSearchResponse.class)
                .itemsOrEmpty())
        .hasSize(1);

    GitHubReleaseResponse published =
        mapper.readValue(
            """
            {"draft":false,"published_at":"2026-11-01T10:00:00Z","created_at":"2026-10-31T10:00:00Z"}
            """,
            GitHubReleaseResponse.class);
    assertThat(published.timestamp()).isEqualTo(Instant.parse("2026-11-01T10:00:00Z"));
    assertThat(
            mapper
                .readValue(
                    """
                    {"draft":true,"published_at":null,"created_at":"2026-10-31T10:00:00Z"}
                    """,
                    GitHubReleaseResponse.class)
                .timestamp())
        .isEqualTo(Instant.parse("2026-10-31T10:00:00Z"));

    GitHubSidebarResponse sidebar =
        mapper.readValue(
            """
            {"contributors":{"contributorCount":4}}
            """,
            GitHubSidebarResponse.class);
    assertThat(sidebar.contributors().contributorCount()).isEqualTo(4);

    assertThat(
            mapper
                .readValue(
                    """
                    {"path":"/docs","title":"Docs","count":2,"uniques":1}
                    """,
                    GitHubPathResponse.class)
                .path())
        .isEqualTo("/docs");
    assertThat(
            mapper
                .readValue(
                    """
                    {"referrer":"github.com","count":5,"uniques":3}
                    """,
                    GitHubReferrerResponse.class)
                .referrer())
        .isEqualTo("github.com");
    assertThat(new GitHubSocialPreview(true, "https://img.example/og.png").custom()).isTrue();

    GitHubSocialPreviewResponse social =
        mapper.readValue(
            """
            {
              "data": {
                "repository": {
                  "usesCustomOpenGraphImage": true,
                  "openGraphImageUrl": "https://img.example/og.png"
                }
              },
              "errors": [{"message":"ok"}]
            }
            """,
            GitHubSocialPreviewResponse.class);
    assertThat(social.data().repository().usesCustomOpenGraphImage()).isTrue();
    assertThat(social.errors()).hasSize(1);

    assertThat(
            mapper
                .readValue(
                    """
                    {
                      "id": 9,
                      "number": 1,
                      "title": "help",
                      "state": "open",
                      "html_url": "https://github.com/a/b/issues/1",
                      "created_at": "2026-11-01T00:00:00Z",
                      "user": {"id": 1, "login": "alice", "type": "User"},
                      "labels": null,
                      "pull_request": null
                    }
                    """,
                    GitHubIssueItem.class)
                .hasGoodFirstIssueLabel())
        .isFalse();

    assertThat(GitHubCommunityProfileResponse.empty().filesOrEmpty().hasReadme()).isFalse();
    assertThat(
            mapper
                .readValue(
                    """
                    {"files":null}
                    """,
                    GitHubCommunityProfileResponse.class)
                .filesOrEmpty()
                .hasLicense())
        .isFalse();
    GitHubCommunityProfileResponse profile =
        mapper.readValue(
            """
            {
              "files": {
                "readme": {},
                "license": {},
                "code_of_conduct": {},
                "contributing": {},
                "issue_template": {},
                "pull_request_template": {}
              }
            }
            """,
            GitHubCommunityProfileResponse.class);
    var files = profile.filesOrEmpty();
    assertThat(files.hasReadme()).isTrue();
    assertThat(files.hasLicense()).isTrue();
    assertThat(files.hasCodeOfConduct()).isTrue();
    assertThat(files.hasContributing()).isTrue();
    assertThat(files.hasIssueTemplate()).isTrue();
    assertThat(files.hasPullRequestTemplate()).isTrue();
    assertThat(List.of(files)).isNotEmpty();
  }
}
