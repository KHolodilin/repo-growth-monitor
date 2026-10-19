import { describe, expect, it } from "vitest";
import {
  missingTrafficBadge,
  partialDayTooltip,
  portfolioNoticeText,
  repositoryProblemBadge,
  repositoryProblemLines,
  repositoryGapText,
  trafficJobWarning,
  trafficWarningDate,
} from "./trafficGap";

describe("traffic gap copy", () => {
  it("names a trailing gap, a hole, a failure, and a missed run", () => {
    expect(missingTrafficBadge(1)).toBe("1 repository missing traffic data");
    expect(missingTrafficBadge(2)).toBe("2 repositories missing traffic data");
    expect(portfolioNoticeText({
      reason: "GITHUB_DATA_MISSING",
      date: "2026-10-18",
      lastTrafficDate: "2026-10-17",
    })).toBe("No GitHub traffic for 18 Oct. Last data Oct 17");
    expect(portfolioNoticeText({
      reason: "GITHUB_DATA_MISSING",
      date: "2026-10-18",
      lastTrafficDate: "2026-10-15",
    })).toBe("No GitHub traffic for 16 Oct–18 Oct. Last data Oct 15");
    expect(repositoryProblemBadge(1)).toBe("1 repository");
    expect(repositoryProblemBadge(2)).toBe("2 repositories");
    expect(repositoryProblemLines({
      trafficNotice: { reason: "GITHUB_DATA_MISSING", date: "2026-10-18", lastTrafficDate: "2026-10-17" },
      collectionStatus: "PARTIAL",
      jobs: [
        { jobType: "TRAFFIC", status: "SUCCESS" },
        { jobType: "REFERRERS", status: "FAILED" },
      ],
    })).toEqual([
      "No GitHub traffic for 18 Oct. Last data Oct 17",
      "Partial collection: Referrers ✕",
    ]);
    expect(repositoryProblemLines({
      trafficNotice: { reason: "MISSING_DAY", date: "2026-10-15", lastTrafficDate: null },
      collectionStatus: "SUCCESS",
    })).toEqual([]);
    expect(repositoryProblemLines({ collectionStatus: "PARTIAL", jobs: [] })).toEqual(["Partial collection"]);
    expect(repositoryProblemLines({
      collectionStatus: "PARTIAL",
      jobs: [
        { jobType: "GROWTH_EVENTS", status: "FAILED", errorMessage: "Invalid GitHub search query" },
        { jobType: "TRAFFIC", status: "SUCCESS" },
        { jobType: "REFERRERS", status: "FAILED", errorMessage: "rate limit" },
      ],
    })).toEqual([
      "Partial collection: Growth Events ✕: Could not load issues, Referrers ✕: rate limit",
    ]);
    expect(portfolioNoticeText({ reason: "MISSING_DAY", date: "2026-10-15", lastTrafficDate: null })).toBe(
      "No GitHub traffic for 15 Oct.",
    );
    expect(portfolioNoticeText({ reason: "COLLECTION_FAILED", date: "2026-10-19", lastTrafficDate: "2026-10-18" })).toBe(
      "Collection failed for Oct 19.",
    );
    expect(portfolioNoticeText({ reason: "COLLECTION_NOT_EXECUTED", date: "2026-10-19", lastTrafficDate: null })).toBe(
      "Collection not executed for Oct 19.",
    );
    expect(partialDayTooltip(2, "2026-10-18")).toBe("Partial data — 2 repositories missing traffic data for 18 Oct");
    expect(partialDayTooltip(1, "2026-10-18")).toBe("Partial data — 1 repository missing traffic data for 18 Oct");
    expect(repositoryGapText({ reason: "GITHUB_DATA_MISSING", date: "2026-10-18", lastTrafficDate: null })).toBe(
      "GitHub traffic data is missing for Oct 18 (T-1).",
    );
    expect(repositoryGapText({ reason: "COLLECTION_NOT_EXECUTED", date: "2026-10-19", lastTrafficDate: null })).toBe(
      "Collection not executed for Oct 19.",
    );
    expect(repositoryGapText({ reason: "COLLECTION_FAILED", date: "2026-10-19", lastTrafficDate: null })).toBe(
      "Collection failed for Oct 19.",
    );
    expect(trafficJobWarning({ reason: "GITHUB_DATA_MISSING", date: "2026-10-18", lastTrafficDate: null })).toBe(
      "Traffic — GitHub data missing for Oct 18 (T-1)",
    );
  });

  it("puts the repository warning on yesterday only when that point is absent", () => {
    const points = [{ date: "2026-10-17" }];
    expect(trafficWarningDate(points, { reason: "GITHUB_DATA_MISSING", date: "2026-10-18", lastTrafficDate: null })).toBe(
      "2026-10-18",
    );
    expect(trafficWarningDate(points, { reason: "COLLECTION_FAILED", date: "2026-10-19", lastTrafficDate: null })).toBe(
      "2026-10-18",
    );
    expect(trafficWarningDate(
      [{ date: "2026-10-18" }],
      { reason: "COLLECTION_FAILED", date: "2026-10-19", lastTrafficDate: null },
    )).toBeNull();
    expect(trafficWarningDate(points, { reason: "MISSING_DAY", date: "2026-10-15", lastTrafficDate: null })).toBeNull();
    expect(trafficWarningDate(points, null)).toBeNull();
  });
});
