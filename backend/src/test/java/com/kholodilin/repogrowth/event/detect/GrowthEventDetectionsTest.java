package com.kholodilin.repogrowth.event.detect;

import com.kholodilin.repogrowth.event.domain.GrowthEventCatalog;
import com.kholodilin.repogrowth.event.domain.GrowthEventState;
import com.kholodilin.repogrowth.github.model.GitHubContributorItem;
import com.kholodilin.repogrowth.github.model.GitHubIssueItem;
import com.kholodilin.repogrowth.github.model.GitHubLabelItem;
import com.kholodilin.repogrowth.github.model.GitHubLicenseResponse;
import com.kholodilin.repogrowth.github.model.GitHubOwnerResponse;
import com.kholodilin.repogrowth.github.model.GitHubPullItem;
import com.kholodilin.repogrowth.github.model.GitHubReleaseItem;
import com.kholodilin.repogrowth.github.model.GitHubRepositoryResponse;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GrowthEventDetectionsTest {

    private static final Instant NOW = Instant.parse("2026-08-31T12:00:00Z");

    @Test
    void firstCollectDoesNotEmitHistoricalDiscoverabilityEvents() {
        GitHubActivitySnapshot current = snapshot(
                repo("new description", List.of("java"), "https://example.com", 20, 1),
                "huge readme",
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        List<CandidateEvent> events = GrowthEventDetections.detect(GrowthEventState.empty(), current, "acme", NOW);
        assertThat(events).extracting(CandidateEvent::type)
                .doesNotContain(
                        GrowthEventCatalog.README_SIGNIFICANTLY_CHANGED,
                        GrowthEventCatalog.DESCRIPTION_CHANGED,
                        GrowthEventCatalog.RELEASE_PUBLISHED
                );
    }

    @Test
    void firstCollectCreatesFirstExternalContributorWhenObserved() {
        GitHubActivitySnapshot current = snapshot(
                repo("demo", List.of(), null, 1, 0),
                "readme",
                List.of(),
                List.of(),
                List.of(),
                List.of(new GitHubContributorItem("alice", "User", null))
        );
        List<CandidateEvent> events = GrowthEventDetections.detect(GrowthEventState.empty(), current, "acme", NOW);
        assertThat(events).extracting(CandidateEvent::type)
                .containsExactly(GrowthEventCatalog.FIRST_EXTERNAL_CONTRIBUTOR);
    }

    @Test
    void discoverabilityEventsUseGithubTimestamps() {
        GrowthEventState previous = new GrowthEventState(
                true, "A".repeat(80), "old-sha", "old", List.of("java"), null,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of("acme"),
                5, 0, 1, true, true, List.of(), List.of(), List.of(), Boolean.FALSE, null
        );
        Instant committed = Instant.parse("2026-09-21T18:00:00Z");
        Instant updated = Instant.parse("2026-09-21T19:00:00Z");
        GitHubActivitySnapshot current = snapshot(
                new GitHubRepositoryResponse(
                        200L, "demo", "acme/demo", "new", false, "public", "main", "Java",
                        false, false, 5, 0, 0, 0, "https://github.com/acme/demo",
                        Instant.parse("2024-01-01T00:00:00Z"), updated, updated,
                        new GitHubOwnerResponse(100L, "acme", "User", null, null),
                        List.of("java"), null, new GitHubLicenseResponse("mit", "MIT License", "MIT")
                ),
                "B".repeat(80),
                "new-sha",
                committed,
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        List<CandidateEvent> events = GrowthEventDetections.detect(previous, current, "acme", NOW);
        assertThat(events)
                .filteredOn(event -> GrowthEventCatalog.README_SIGNIFICANTLY_CHANGED.equals(event.type()))
                .extracting(CandidateEvent::eventAt)
                .containsExactly(committed);
        assertThat(events)
                .filteredOn(event -> GrowthEventCatalog.DESCRIPTION_CHANGED.equals(event.type()))
                .extracting(CandidateEvent::eventAt)
                .containsExactly(updated);
    }

    @Test
    void initializedStateDetectsDescriptionAndExternalPr() {
        GrowthEventState previous = new GrowthEventState(
                true, "readme", "sha", "old", List.of("java"), null,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of("acme"),
                5, 0, 1, true, true, List.of(), List.of(), List.of(), Boolean.FALSE, null
        );
        Instant opened = Instant.parse("2026-08-30T10:00:00Z");
        Instant merged = Instant.parse("2026-08-30T11:00:00Z");
        GitHubActivitySnapshot current = snapshot(
                repo("new", List.of("java"), null, 5, 0),
                "readme",
                List.of(),
                List.of(new GitHubPullItem(
                        77L, 3, "Help", "closed", true, "https://github.com/acme/demo/pull/3",
                        opened, merged, new GitHubOwnerResponse(2L, "alice", "User", null, null)
                )),
                List.of(),
                List.of(new GitHubContributorItem("alice", "User", null))
        );
        List<CandidateEvent> events = GrowthEventDetections.detect(previous, current, "acme", NOW);
        assertThat(events).extracting(CandidateEvent::type)
                .containsExactlyInAnyOrder(
                        GrowthEventCatalog.DESCRIPTION_CHANGED,
                        GrowthEventCatalog.EXTERNAL_PR_OPENED,
                        GrowthEventCatalog.EXTERNAL_PR_MERGED
                );
    }

    @Test
    void goodFirstIssueIsDetectedForNewLabelOnKnownIssue() {
        GrowthEventState previous = new GrowthEventState(
                true, "readme", "sha", "demo", List.of(), null,
                List.of(), List.of(9L), List.of(), List.of(), List.of(), List.of(),
                1, 0, 1, true, true, List.of(), List.of(), List.of(), Boolean.FALSE, null
        );
        GitHubIssueItem issue = new GitHubIssueItem(
                9L, 1, "Starter", "open", "https://github.com/acme/demo/issues/1",
                Instant.parse("2026-08-01T00:00:00Z"),
                new GitHubOwnerResponse(1L, "acme", "User", null, null),
                List.of(new GitHubLabelItem("Good First Issue")),
                null
        );
        List<CandidateEvent> events = GrowthEventDetections.detect(
                previous,
                snapshot(repo("demo", List.of(), null, 1, 0), "readme", List.of(issue), List.of(), List.of(), List.of()),
                "acme",
                NOW
        );
        assertThat(events).extracting(CandidateEvent::type)
                .contains(GrowthEventCatalog.GOOD_FIRST_ISSUE_PUBLISHED);
    }

    @Test
    void starMilestoneFiresOnlyAfterInitialization() {
        GrowthEventState previous = new GrowthEventState(
                true, "readme", "sha", "demo", List.of(), null,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                9, 0, 1, true, true, List.of(), List.of(), List.of(), Boolean.FALSE, null
        );
        List<CandidateEvent> events = GrowthEventDetections.detect(
                previous,
                snapshot(repo("demo", List.of(), null, 10, 0), "readme", List.of(), List.of(), List.of(), List.of()),
                "acme",
                NOW
        );
        assertThat(events).extracting(CandidateEvent::type).contains(GrowthEventCatalog.STAR_MILESTONE);
        assertThat(events).extracting(CandidateEvent::externalId).contains("star:10");
    }

    @Test
    void newReleaseIsDetectedOnce() {
        GrowthEventState previous = initialized();
        GitHubReleaseItem release = new GitHubReleaseItem(
                5L, false, "v1.0.0", "1.0.0", "https://github.com/acme/demo/releases/tag/v1.0.0",
                Instant.parse("2026-08-20T00:00:00Z"), Instant.parse("2026-08-20T00:00:00Z")
        );
        List<CandidateEvent> first = GrowthEventDetections.detect(
                previous,
                snapshot(repo("demo", List.of(), null, 1, 0), "readme", List.of(), List.of(), List.of(release), List.of()),
                "acme",
                NOW
        );
        GrowthEventState next = GrowthEventDetections.snapshot(
                previous,
                snapshot(repo("demo", List.of(), null, 1, 0), "readme", List.of(), List.of(), List.of(release), List.of()),
                "acme"
        );
        List<CandidateEvent> second = GrowthEventDetections.detect(
                next,
                snapshot(repo("demo", List.of(), null, 1, 0), "readme", List.of(), List.of(), List.of(release), List.of()),
                "acme",
                NOW
        );
        assertThat(first).extracting(CandidateEvent::type).contains(GrowthEventCatalog.RELEASE_PUBLISHED);
        assertThat(second).extracting(CandidateEvent::type).doesNotContain(GrowthEventCatalog.RELEASE_PUBLISHED);
    }

    @Test
    void socialPreviewIsRememberedBeforeItIsCompared() {
        GrowthEventState previous = initialized();
        GitHubActivitySnapshot current = previewSnapshot(null, null);
        assertThat(GrowthEventDetections.detect(previous, current, "acme", NOW)).extracting(CandidateEvent::type)
                .doesNotContain(GrowthEventCatalog.SOCIAL_PREVIEW_CHANGED);
        GrowthEventState stored = GrowthEventDetections.snapshot(previous, previewSnapshot(true, "https://img/a.png"), "acme");
        assertThat(stored.customSocialPreview()).isTrue();
        assertThat(GrowthEventDetections.detect(stored, previewSnapshot(true, "https://img/a.png"), "acme", NOW))
                .extracting(CandidateEvent::type)
                .doesNotContain(GrowthEventCatalog.SOCIAL_PREVIEW_CHANGED);
    }

    @Test
    void socialPreviewSetChangeAndRemoval() {
        GrowthEventState none = withPreview(false, null);
        CandidateEvent set = GrowthEventDetections.detect(none, previewSnapshot(true, "https://img/a.png"), "acme", NOW)
                .stream().filter(event -> GrowthEventCatalog.SOCIAL_PREVIEW_CHANGED.equals(event.type())).findFirst().orElseThrow();
        assertThat(set.title()).isEqualTo("Social preview set");

        GrowthEventState custom = withPreview(true, "https://img/a.png");
        CandidateEvent changed = GrowthEventDetections.detect(custom, previewSnapshot(true, "https://img/b.png"), "acme", NOW)
                .stream().filter(event -> GrowthEventCatalog.SOCIAL_PREVIEW_CHANGED.equals(event.type())).findFirst().orElseThrow();
        assertThat(changed.title()).isEqualTo("Social preview changed");

        CandidateEvent removed = GrowthEventDetections.detect(custom, previewSnapshot(false, "https://generated"), "acme", NOW)
                .stream().filter(event -> GrowthEventCatalog.SOCIAL_PREVIEW_CHANGED.equals(event.type())).findFirst().orElseThrow();
        assertThat(removed.title()).isEqualTo("Social preview removed");

        assertThat(GrowthEventDetections.detect(none, previewSnapshot(false, "https://generated"), "acme", NOW))
                .extracting(CandidateEvent::type)
                .doesNotContain(GrowthEventCatalog.SOCIAL_PREVIEW_CHANGED);
        assertThat(GrowthEventCatalog.automaticDefaults().get(GrowthEventCatalog.SOCIAL_PREVIEW_CHANGED)).isTrue();
    }

    @Test
    void oldStateJsonWithoutSocialPreviewDoesNotInventAValue() throws Exception {
        String json = """
                {"initialized":true,"readmeText":"readme","readmeSha":"sha","description":"demo","topics":[],
                "stars":1,"forks":0,"contributors":1,"firstExternalContributorRecorded":true,"firstExternalForkRecorded":true}
                """;
        GrowthEventState state = tools.jackson.databind.json.JsonMapper.builder().build().readValue(json, GrowthEventState.class);
        assertThat(state.customSocialPreview()).isNull();
        assertThat(GrowthEventDetections.detect(state, previewSnapshot(true, "https://img/a.png"), "acme", NOW))
                .extracting(CandidateEvent::type)
                .doesNotContain(GrowthEventCatalog.SOCIAL_PREVIEW_CHANGED);
        assertThat(GrowthEventDetections.snapshot(state, previewSnapshot(true, "https://img/a.png"), "acme").customSocialPreview()).isTrue();
    }

    private static GitHubActivitySnapshot previewSnapshot(Boolean custom, String url) {
        return new GitHubActivitySnapshot(
                repo("demo", List.of(), null, 1, 0),
                "readme",
                "sha",
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                custom,
                url
        );
    }

    private static GrowthEventState withPreview(boolean custom, String url) {
        GrowthEventState base = initialized();
        return new GrowthEventState(
                base.initialized(), base.readmeText(), base.readmeSha(), base.description(), base.topics(), base.homepage(),
                base.gfiIssueIds(), base.issueIds(), base.openedPrIds(), base.mergedPrIds(), base.releaseIds(), base.contributorLogins(),
                base.stars(), base.forks(), base.contributors(), base.firstExternalContributorRecorded(), base.firstExternalForkRecorded(),
                base.firedStarMilestones(), base.firedForkMilestones(), base.firedContributorMilestones(),
                custom, url
        );
    }

    private static GrowthEventState initialized() {
        return new GrowthEventState(
                true, "readme", "sha", "demo", List.of(), null,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                1, 0, 1, true, true, List.of(), List.of(), List.of(), Boolean.FALSE, null
        );
    }

    private static GitHubActivitySnapshot snapshot(
            GitHubRepositoryResponse repository,
            String readme,
            List<GitHubIssueItem> issues,
            List<GitHubPullItem> pulls,
            List<GitHubReleaseItem> releases,
            List<GitHubContributorItem> contributors
    ) {
        return snapshot(repository, readme, "sha", null, issues, pulls, releases, contributors);
    }

    private static GitHubActivitySnapshot snapshot(
            GitHubRepositoryResponse repository,
            String readme,
            String readmeSha,
            Instant readmeCommittedAt,
            List<GitHubIssueItem> issues,
            List<GitHubPullItem> pulls,
            List<GitHubReleaseItem> releases,
            List<GitHubContributorItem> contributors
    ) {
        return new GitHubActivitySnapshot(
                repository, readme, readmeSha, readmeCommittedAt, issues, pulls, releases, contributors, Boolean.FALSE, null
        );
    }

    private static GitHubRepositoryResponse repo(String description, List<String> topics, String homepage, int stars, int forks) {
        return new GitHubRepositoryResponse(
                200L, "demo", "acme/demo", description, false, "public", "main", "Java",
                false, false, stars, 0, forks, 0, "https://github.com/acme/demo",
                Instant.parse("2024-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"),
                new GitHubOwnerResponse(100L, "acme", "User", null, null),
                topics, homepage, new GitHubLicenseResponse("mit", "MIT License", "MIT")
        );
    }
}
