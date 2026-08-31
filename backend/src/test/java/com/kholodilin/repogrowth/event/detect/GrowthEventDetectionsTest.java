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
    void initializedStateDetectsDescriptionAndExternalPr() {
        GrowthEventState previous = new GrowthEventState(
                true, "readme", "sha", "old", List.of("java"), null,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of("acme"),
                5, 0, 1, true, true, List.of(), List.of(), List.of()
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
                1, 0, 1, true, true, List.of(), List.of(), List.of()
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
                9, 0, 1, true, true, List.of(), List.of(), List.of()
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

    private static GrowthEventState initialized() {
        return new GrowthEventState(
                true, "readme", "sha", "demo", List.of(), null,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                1, 0, 1, true, true, List.of(), List.of(), List.of()
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
        return new GitHubActivitySnapshot(repository, readme, "sha", issues, pulls, releases, contributors);
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
