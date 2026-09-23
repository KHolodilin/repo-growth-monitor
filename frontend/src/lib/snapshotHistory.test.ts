import { describe, expect, it } from "vitest";
import type { SnapshotHistoryCell, SnapshotHistoryRow } from "./api";
import { cellValue, chartValue, defaultTopKeys, formatSnapshotDelta, latestValue } from "./snapshotHistory";

const cell = (partial: Partial<SnapshotHistoryCell>): SnapshotHistoryCell => ({
  date: "2026-09-23",
  visitors: 3,
  views: 8,
  visitorsDelta: 1,
  viewsDelta: 2,
  firstSeen: false,
  ...partial,
});

describe("snapshotHistory", () => {
  it("reads metric values and chart deltas", () => {
    expect(cellValue(cell({}), "VISITORS")).toBe(3);
    expect(cellValue(cell({}), "VIEWS")).toBe(8);
    expect(chartValue(cell({ visitors: null }), "VISITORS")).toBeNull();
    expect(chartValue(cell({ firstSeen: true }), "VISITORS")).toBe(3);
    expect(chartValue(cell({ visitorsDelta: null }), "VISITORS")).toBeNull();
    expect(chartValue(cell({ visitorsDelta: -2 }), "VISITORS")).toBeNull();
    expect(chartValue(cell({}), "VISITORS")).toBe(1);
  });

  it("picks latest values and default keys", () => {
    const empty: SnapshotHistoryRow = { key: "empty", cells: [cell({ visitors: null, views: null })] };
    const github: SnapshotHistoryRow = { key: "github.com", cells: [cell({ visitors: null }), cell({})] };
    const other: SnapshotHistoryRow = { key: "other", cells: [cell({ visitors: 1, views: 1 })] };
    expect(latestValue(empty, "VISITORS")).toBe(0);
    expect(latestValue(github, "VISITORS")).toBe(3);
    expect(defaultTopKeys([other, github, empty], "VISITORS", 2)).toEqual(["github.com", "other"]);
  });

  it("formats snapshot deltas", () => {
    expect(formatSnapshotDelta(cell({ visitors: null }), "VISITORS")).toBeNull();
    expect(formatSnapshotDelta(cell({ firstSeen: true }), "VISITORS")).toEqual({ label: "N", direction: "up" });
    expect(formatSnapshotDelta(cell({ visitorsDelta: 0 }), "VISITORS")).toBeNull();
    expect(formatSnapshotDelta(cell({ visitorsDelta: 4 }), "VISITORS")).toEqual({ label: "↑4", direction: "up" });
    expect(formatSnapshotDelta(cell({ visitorsDelta: -2 }), "VISITORS")).toEqual({ label: "↓2", direction: "down" });
  });
});
