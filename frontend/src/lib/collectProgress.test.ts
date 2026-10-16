import { describe, expect, it } from "vitest";
import { collectButtonLabel, collectionInProgress, headerProgress, type ProgressRow } from "./collectProgress";

const repo = (patch: Partial<ProgressRow> = {}): ProgressRow => ({
  id: 13,
  fullName: "KHolodilin/repo",
  collectionStatus: "SUCCESS",
  jobs: [],
  ...patch,
});

describe("collect progress", () => {
  it("names the header button by scope", () => {
    expect(collectButtonLabel(4, true)).toBe("Collect this repository");
    expect(collectButtonLabel(0, false)).toBe("Select repositories first");
    expect(collectButtonLabel(null, false)).toBe("Collect repositories");
    expect(collectButtonLabel(1, false)).toBe("Collect 1 repository");
    expect(collectButtonLabel(4, false)).toBe("Collect 4 repositories");
  });

  it("recognizes an in-progress collection", () => {
    expect(collectionInProgress(null)).toBe(false);
    expect(collectionInProgress(undefined)).toBe(false);
    expect(collectionInProgress("SUCCESS")).toBe(false);
    expect(collectionInProgress("RUNNING")).toBe(true);
    expect(collectionInProgress("PLANNED")).toBe(true);
  });

  it("counts finished repositories and keeps zero until a run is visible", () => {
    const rows = [
      repo({ collectionStatus: "SUCCESS" }),
      repo({ id: 8, fullName: "KHolodilin/other", collectionStatus: "PLANNED" }),
      repo({ id: 9, fullName: "KHolodilin/third", collectionStatus: "RUNNING", jobs: [{ jobType: "TRAFFIC", status: "FAILED", errorMessage: "  rate limit  " }] }),
      repo({ id: 10, fullName: "KHolodilin/fourth", collectionStatus: null }),
    ];
    expect(headerProgress({ repositoryId: null, rows, watching: false })).toBe("Collected 1 of 4. rate limit");
    expect(headerProgress({ repositoryId: null, rows: rows.map((row) => ({ ...row, collectionStatus: "SUCCESS" })), watching: true })).toBe(
      "Collected 0 of 4",
    );
    expect(headerProgress({ repositoryId: null, rows: [], watching: true })).toBeNull();
    expect(headerProgress({ repositoryId: null, rows, watching: false })).not.toBeNull();
    expect(
      headerProgress({
        repositoryId: null,
        rows: [repo()],
        watching: false,
      }),
    ).toBeNull();
    expect(
      headerProgress({
        repositoryId: null,
        rows: [repo({ collectionStatus: "RUNNING", jobs: [{ jobType: "TRAFFIC", status: "FAILED" }] })],
        watching: false,
      }),
    ).toBe("Collected 0 of 1");
  });

  it("names the repository and each job on its pages", () => {
    const row = repo({
      collectionStatus: "RUNNING",
      jobs: [
        { jobType: "TRAFFIC", status: "SUCCESS" },
        { jobType: "REFERRERS", status: "RUNNING" },
        { jobType: "POPULAR_PATHS", status: "RETRY" },
        { jobType: "REPOSITORY_STATS", status: "FAILED", errorMessage: "missing" },
        { jobType: "GROWTH_EVENTS", status: "FAILED", errorMessage: "  " },
        { jobType: "OTHER", status: "PLANNED" },
        { jobType: "CUSTOM", status: "SUCCESS" },
      ],
    });
    expect(headerProgress({ repositoryId: 13, rows: [row], watching: false })).toBe(
      "Collecting KHolodilin/repo: Traffic done, Referrers running, Popular Paths running, Repository Stats failed: missing, Growth Events failed, CUSTOM done",
    );
    expect(headerProgress({ repositoryId: 13, rows: [repo({ collectionStatus: "RUNNING", jobs: [] })], watching: false })).toBe(
      "Collecting KHolodilin/repo",
    );
    expect(headerProgress({ repositoryId: 13, rows: [repo()], watching: true })).toBe("Collecting KHolodilin/repo");
    expect(headerProgress({ repositoryId: 99, rows: [row], watching: true })).toBe("Collecting this repository");
    expect(headerProgress({ repositoryId: 99, rows: [row], watching: false })).toBeNull();
    expect(
      headerProgress({
        repositoryId: 13,
        rows: [repo({ jobs: [{ jobType: "REFERRERS", status: "FAILED", errorMessage: "rate limit" }] })],
        watching: false,
      }),
    ).toBe("Referrers failed: rate limit");
    expect(headerProgress({ repositoryId: 13, rows: [repo()], watching: false })).toBeNull();
  });
});
