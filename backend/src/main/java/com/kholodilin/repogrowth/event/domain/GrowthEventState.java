package com.kholodilin.repogrowth.event.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GrowthEventState(
        boolean initialized,
        String readmeText,
        String readmeSha,
        String description,
        List<String> topics,
        String homepage,
        List<Long> gfiIssueIds,
        List<Long> issueIds,
        List<Long> openedPrIds,
        List<Long> mergedPrIds,
        List<Long> releaseIds,
        List<String> contributorLogins,
        int stars,
        int forks,
        int contributors,
        boolean firstExternalContributorRecorded,
        boolean firstExternalForkRecorded,
        List<Integer> firedStarMilestones,
        List<Integer> firedForkMilestones,
        List<Integer> firedContributorMilestones
) {
    public static GrowthEventState empty() {
        return new GrowthEventState(
                false,
                null,
                null,
                null,
                List.of(),
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                0,
                0,
                0,
                false,
                false,
                List.of(),
                List.of(),
                List.of()
        );
    }

    public List<String> topicsOrEmpty() {
        return topics == null ? List.of() : topics;
    }

    public List<Long> gfiIssueIdsOrEmpty() {
        return gfiIssueIds == null ? List.of() : gfiIssueIds;
    }

    public List<Long> issueIdsOrEmpty() {
        return issueIds == null ? List.of() : issueIds;
    }

    public List<Long> openedPrIdsOrEmpty() {
        return openedPrIds == null ? List.of() : openedPrIds;
    }

    public List<Long> mergedPrIdsOrEmpty() {
        return mergedPrIds == null ? List.of() : mergedPrIds;
    }

    public List<Long> releaseIdsOrEmpty() {
        return releaseIds == null ? List.of() : releaseIds;
    }

    public List<String> contributorLoginsOrEmpty() {
        return contributorLogins == null ? List.of() : contributorLogins;
    }

    public List<Integer> firedStarMilestonesOrEmpty() {
        return firedStarMilestones == null ? List.of() : firedStarMilestones;
    }

    public List<Integer> firedForkMilestonesOrEmpty() {
        return firedForkMilestones == null ? List.of() : firedForkMilestones;
    }

    public List<Integer> firedContributorMilestonesOrEmpty() {
        return firedContributorMilestones == null ? List.of() : firedContributorMilestones;
    }
}
