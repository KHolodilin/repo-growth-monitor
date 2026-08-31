package com.kholodilin.repogrowth.event.detect;

import com.kholodilin.repogrowth.event.domain.GrowthEventCatalog;
import com.kholodilin.repogrowth.event.domain.GrowthEventState;
import com.kholodilin.repogrowth.github.model.GitHubContributorItem;
import com.kholodilin.repogrowth.github.model.GitHubIssueItem;
import com.kholodilin.repogrowth.github.model.GitHubPullItem;
import com.kholodilin.repogrowth.github.model.GitHubReleaseItem;
import com.kholodilin.repogrowth.github.model.GitHubRepositoryResponse;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class GrowthEventDetections {

    private GrowthEventDetections() {
    }

    public static List<CandidateEvent> detect(
            GrowthEventState previous,
            GitHubActivitySnapshot current,
            String ownerLogin,
            Instant collectedAt
    ) {
        List<CandidateEvent> events = new ArrayList<>();
        if (previous.initialized()) {
            events.addAll(discoverability(previous, current, collectedAt));
            events.addAll(community(previous, current, ownerLogin, collectedAt));
            events.addAll(releases(previous, current));
        }
        events.addAll(milestones(previous, current, ownerLogin, collectedAt));
        return events;
    }

    public static GrowthEventState snapshot(
            GrowthEventState previous,
            GitHubActivitySnapshot current,
            String ownerLogin
    ) {
        GitHubRepositoryResponse repository = current.repository();
        List<Long> gfiIds = current.issuesOrEmpty().stream()
                .filter(issue -> !issue.pullRequestIssue() && issue.hasGoodFirstIssueLabel())
                .map(GitHubIssueItem::id)
                .toList();
        List<Long> issueIds = current.issuesOrEmpty().stream()
                .filter(issue -> !issue.pullRequestIssue())
                .map(GitHubIssueItem::id)
                .toList();
        List<Long> openedPrIds = current.pullsOrEmpty().stream().map(GitHubPullItem::id).toList();
        List<Long> mergedPrIds = current.pullsOrEmpty().stream()
                .filter(GitHubPullItem::mergedPull)
                .map(GitHubPullItem::id)
                .toList();
        List<Long> releaseIds = current.releasesOrEmpty().stream()
                .filter(release -> !Boolean.TRUE.equals(release.draft()))
                .map(GitHubReleaseItem::id)
                .toList();
        List<String> contributorLogins = current.contributorsOrEmpty().stream()
                .map(GitHubContributorItem::login)
                .filter(login -> login != null && !login.isBlank())
                .toList();
        boolean firstContributor = previous.firstExternalContributorRecorded()
                || hasExternalContributor(current, ownerLogin);
        boolean firstFork = previous.firstExternalForkRecorded() || repository.forksCount() > 0;
        return new GrowthEventState(
                true,
                current.readmeText(),
                current.readmeSha(),
                repository.description(),
                repository.topicsOrEmpty(),
                blankToNull(repository.homepage()),
                mergeIds(previous.gfiIssueIdsOrEmpty(), gfiIds),
                mergeIds(previous.issueIdsOrEmpty(), issueIds),
                mergeIds(previous.openedPrIdsOrEmpty(), openedPrIds),
                mergeIds(previous.mergedPrIdsOrEmpty(), mergedPrIds),
                mergeIds(previous.releaseIdsOrEmpty(), releaseIds),
                mergeLogins(previous.contributorLoginsOrEmpty(), contributorLogins),
                repository.stargazersCount(),
                repository.forksCount(),
                contributorLogins.size(),
                firstContributor,
                firstFork,
                crossed(previous.firedStarMilestonesOrEmpty(), previous.stars(), repository.stargazersCount(), GrowthEventCatalog.STAR_THRESHOLDS, previous.initialized()),
                crossed(previous.firedForkMilestonesOrEmpty(), previous.forks(), repository.forksCount(), GrowthEventCatalog.FORK_THRESHOLDS, previous.initialized()),
                crossed(previous.firedContributorMilestonesOrEmpty(), previous.contributors(), contributorLogins.size(), GrowthEventCatalog.CONTRIBUTOR_THRESHOLDS, previous.initialized())
        );
    }

    static List<CandidateEvent> discoverability(GrowthEventState previous, GitHubActivitySnapshot current, Instant collectedAt) {
        List<CandidateEvent> events = new ArrayList<>();
        if (ReadmeSignificance.significant(previous.readmeText(), current.readmeText())) {
            events.add(new CandidateEvent(
                    collectedAt,
                    GrowthEventCatalog.CATEGORY_DISCOVERABILITY,
                    GrowthEventCatalog.README_SIGNIFICANTLY_CHANGED,
                    "README significantly changed",
                    null,
                    null,
                    GrowthEventCatalog.SOURCE_GITHUB,
                    "readme:" + Objects.requireNonNullElse(current.readmeSha(), Integer.toHexString(ReadmeSignificance.normalize(current.readmeText()).hashCode()))
            ));
        }
        GitHubRepositoryResponse repository = current.repository();
        if (!Objects.equals(normalize(previous.description()), normalize(repository.description()))) {
            events.add(new CandidateEvent(
                    collectedAt,
                    GrowthEventCatalog.CATEGORY_DISCOVERABILITY,
                    GrowthEventCatalog.DESCRIPTION_CHANGED,
                    "Repository description changed",
                    repository.description(),
                    repository.htmlUrl(),
                    GrowthEventCatalog.SOURCE_GITHUB,
                    "description:" + Objects.requireNonNullElse(repository.description(), "")
            ));
        }
        if (!previous.topicsOrEmpty().equals(repository.topicsOrEmpty())) {
            events.add(new CandidateEvent(
                    collectedAt,
                    GrowthEventCatalog.CATEGORY_DISCOVERABILITY,
                    GrowthEventCatalog.TOPICS_CHANGED,
                    "Repository topics changed",
                    String.join(", ", repository.topicsOrEmpty()),
                    repository.htmlUrl(),
                    GrowthEventCatalog.SOURCE_GITHUB,
                    "topics:" + String.join(",", repository.topicsOrEmpty())
            ));
        }
        if (!Objects.equals(normalize(previous.homepage()), normalize(repository.homepage()))) {
            events.add(new CandidateEvent(
                    collectedAt,
                    GrowthEventCatalog.CATEGORY_DISCOVERABILITY,
                    GrowthEventCatalog.HOMEPAGE_CHANGED,
                    "Repository homepage changed",
                    repository.homepage(),
                    repository.htmlUrl(),
                    GrowthEventCatalog.SOURCE_GITHUB,
                    "homepage:" + Objects.requireNonNullElse(repository.homepage(), "")
            ));
        }
        return events;
    }

    static List<CandidateEvent> community(
            GrowthEventState previous,
            GitHubActivitySnapshot current,
            String ownerLogin,
            Instant collectedAt
    ) {
        List<CandidateEvent> events = new ArrayList<>();
        Set<Long> knownIssues = new HashSet<>(previous.issueIdsOrEmpty());
        Set<Long> knownGfi = new HashSet<>(previous.gfiIssueIdsOrEmpty());
        for (GitHubIssueItem issue : current.issuesOrEmpty()) {
            if (issue.pullRequestIssue()) {
                continue;
            }
            boolean newIssue = !knownIssues.contains(issue.id());
            boolean newGfi = issue.hasGoodFirstIssueLabel() && !knownGfi.contains(issue.id());
            if (newGfi && ("open".equalsIgnoreCase(issue.state()) || newIssue)) {
                events.add(new CandidateEvent(
                        issue.createdAt() != null ? issue.createdAt() : collectedAt,
                        GrowthEventCatalog.CATEGORY_COMMUNITY,
                        GrowthEventCatalog.GOOD_FIRST_ISSUE_PUBLISHED,
                        "Good first issue published",
                        issue.title(),
                        issue.htmlUrl(),
                        GrowthEventCatalog.SOURCE_GITHUB,
                        "gfi:" + issue.id()
                ));
            }
            if (newIssue && issue.user() != null && ExternalActor.isExternal(issue.user().login(), issue.user().type(), ownerLogin)) {
                events.add(new CandidateEvent(
                        issue.createdAt() != null ? issue.createdAt() : collectedAt,
                        GrowthEventCatalog.CATEGORY_COMMUNITY,
                        GrowthEventCatalog.EXTERNAL_ISSUE_OPENED,
                        "External issue opened",
                        issue.title(),
                        issue.htmlUrl(),
                        GrowthEventCatalog.SOURCE_GITHUB,
                        "issue:" + issue.id()
                ));
            }
        }
        Set<Long> knownOpened = new HashSet<>(previous.openedPrIdsOrEmpty());
        Set<Long> knownMerged = new HashSet<>(previous.mergedPrIdsOrEmpty());
        for (GitHubPullItem pull : current.pullsOrEmpty()) {
            if (pull.user() == null || !ExternalActor.isExternal(pull.user().login(), pull.user().type(), ownerLogin)) {
                continue;
            }
            if (!knownOpened.contains(pull.id())) {
                events.add(new CandidateEvent(
                        pull.createdAt() != null ? pull.createdAt() : collectedAt,
                        GrowthEventCatalog.CATEGORY_COMMUNITY,
                        GrowthEventCatalog.EXTERNAL_PR_OPENED,
                        "External pull request opened",
                        pull.title(),
                        pull.htmlUrl(),
                        GrowthEventCatalog.SOURCE_GITHUB,
                        "pr-open:" + pull.id()
                ));
            }
            if (pull.mergedPull() && !knownMerged.contains(pull.id())) {
                events.add(new CandidateEvent(
                        pull.mergedAt() != null ? pull.mergedAt() : collectedAt,
                        GrowthEventCatalog.CATEGORY_COMMUNITY,
                        GrowthEventCatalog.EXTERNAL_PR_MERGED,
                        "External pull request merged",
                        pull.title(),
                        pull.htmlUrl(),
                        GrowthEventCatalog.SOURCE_GITHUB,
                        "pr-merged:" + pull.id()
                ));
            }
        }
        return events;
    }

    static List<CandidateEvent> releases(GrowthEventState previous, GitHubActivitySnapshot current) {
        Set<Long> known = new HashSet<>(previous.releaseIdsOrEmpty());
        List<CandidateEvent> events = new ArrayList<>();
        for (GitHubReleaseItem release : current.releasesOrEmpty()) {
            if (Boolean.TRUE.equals(release.draft()) || known.contains(release.id())) {
                continue;
            }
            events.add(new CandidateEvent(
                    release.timestamp() != null ? release.timestamp() : Instant.now(),
                    GrowthEventCatalog.CATEGORY_RELEASE,
                    GrowthEventCatalog.RELEASE_PUBLISHED,
                    "Release " + release.displayName(),
                    release.tagName(),
                    release.htmlUrl(),
                    GrowthEventCatalog.SOURCE_GITHUB,
                    "release:" + release.id()
            ));
        }
        return events;
    }

    static List<CandidateEvent> milestones(
            GrowthEventState previous,
            GitHubActivitySnapshot current,
            String ownerLogin,
            Instant collectedAt
    ) {
        List<CandidateEvent> events = new ArrayList<>();
        GitHubRepositoryResponse repository = current.repository();
        if (!previous.firstExternalContributorRecorded() && hasExternalContributor(current, ownerLogin)) {
            events.add(new CandidateEvent(
                    collectedAt,
                    GrowthEventCatalog.CATEGORY_COMMUNITY,
                    GrowthEventCatalog.FIRST_EXTERNAL_CONTRIBUTOR,
                    "First external contributor",
                    null,
                    repository.htmlUrl(),
                    GrowthEventCatalog.SOURCE_SYSTEM,
                    "first-external-contributor"
            ));
        }
        if (!previous.firstExternalForkRecorded() && repository.forksCount() > 0 && previous.initialized()) {
            events.add(new CandidateEvent(
                    collectedAt,
                    GrowthEventCatalog.CATEGORY_MILESTONE,
                    GrowthEventCatalog.FIRST_EXTERNAL_FORK,
                    "First fork",
                    null,
                    repository.htmlUrl(),
                    GrowthEventCatalog.SOURCE_SYSTEM,
                    "first-external-fork"
            ));
        }
        if (previous.initialized()) {
            for (int threshold : newlyCrossed(previous.firedStarMilestonesOrEmpty(), previous.stars(), repository.stargazersCount(), GrowthEventCatalog.STAR_THRESHOLDS)) {
                events.add(milestone(collectedAt, GrowthEventCatalog.STAR_MILESTONE, threshold + " stars", "star:" + threshold, repository.htmlUrl()));
            }
            for (int threshold : newlyCrossed(previous.firedForkMilestonesOrEmpty(), previous.forks(), repository.forksCount(), GrowthEventCatalog.FORK_THRESHOLDS)) {
                events.add(milestone(collectedAt, GrowthEventCatalog.FORK_MILESTONE, threshold + " forks", "fork:" + threshold, repository.htmlUrl()));
            }
            int contributorCount = current.contributorsOrEmpty().size();
            for (int threshold : newlyCrossed(previous.firedContributorMilestonesOrEmpty(), previous.contributors(), contributorCount, GrowthEventCatalog.CONTRIBUTOR_THRESHOLDS)) {
                events.add(milestone(collectedAt, GrowthEventCatalog.CONTRIBUTOR_MILESTONE, threshold + " contributors", "contributors:" + threshold, repository.htmlUrl()));
            }
        }
        return events;
    }

    private static CandidateEvent milestone(Instant at, String type, String title, String externalId, String url) {
        return new CandidateEvent(
                at,
                GrowthEventCatalog.CATEGORY_MILESTONE,
                type,
                title,
                null,
                url,
                GrowthEventCatalog.SOURCE_SYSTEM,
                externalId
        );
    }

    private static boolean hasExternalContributor(GitHubActivitySnapshot current, String ownerLogin) {
        return current.contributorsOrEmpty().stream().anyMatch(contributor ->
                ExternalActor.isExternal(contributor.login(), contributor.type(), ownerLogin));
    }

    private static List<Integer> newlyCrossed(List<Integer> already, int previous, int current, List<Integer> thresholds) {
        List<Integer> result = new ArrayList<>();
        for (int threshold : thresholds) {
            if (already.contains(threshold)) {
                continue;
            }
            if (previous < threshold && current >= threshold) {
                result.add(threshold);
            }
        }
        return result;
    }

    private static List<Integer> crossed(List<Integer> already, int previous, int current, List<Integer> thresholds, boolean initialized) {
        Set<Integer> next = new HashSet<>(already);
        if (initialized) {
            next.addAll(newlyCrossed(already, previous, current, thresholds));
        }
        return next.stream().sorted().toList();
    }

    private static List<Long> mergeIds(List<Long> previous, List<Long> current) {
        Set<Long> merged = new HashSet<>(previous);
        merged.addAll(current);
        return merged.stream().sorted().toList();
    }

    private static List<String> mergeLogins(List<String> previous, List<String> current) {
        Set<String> merged = new HashSet<>(previous);
        merged.addAll(current);
        return merged.stream().sorted().toList();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
