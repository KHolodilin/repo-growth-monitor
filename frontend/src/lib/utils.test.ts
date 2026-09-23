import { afterEach, describe, expect, it, vi } from "vitest";
import {
  activityClass,
  calendarDates,
  cn,
  formatActivity,
  formatActivityPresentation,
  formatChartAxisDate,
  formatDelta,
  formatGrowth,
  formatNumber,
  formatPositionDelta,
  formatQueryRankChange,
  formatRank,
  formatRelativeTime,
  formatSyncTime,
  growthClass,
} from "./utils";

describe("utils", () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it("merges class names", () => {
    expect(cn("a", false && "b", "c")).toContain("a");
  });

  it("formats numbers ranks growth and deltas", () => {
    expect(formatNumber(null)).toBe("—");
    expect(formatNumber(11918)).toBe("11,918");
    expect(formatRank(undefined)).toBe(">50");
    expect(formatRank(3, 20)).toBe("#3");
    expect(formatGrowth(null)).toBeNull();
    expect(formatGrowth(Number.NaN)).toBeNull();
    expect(formatGrowth(12.54)).toEqual({ direction: "up", label: "↑ 12.5%" });
    expect(formatGrowth(-1)).toEqual({ direction: "down", label: "↓ 1%" });
    expect(formatGrowth(0)).toEqual({ direction: "flat", label: "→ 0%" });
    expect(formatDelta(null)).toBe("—");
    expect(formatDelta(2)).toBe("↑2");
    expect(formatDelta(-3)).toBe("↓3");
    expect(formatDelta(0)).toBe("0");
    expect(formatPositionDelta(null)).toBe("NEW");
    expect(formatPositionDelta(1)).toBe("↑1");
  });

  it("formats query rank changes and activity", () => {
    expect(formatQueryRankChange(null).label).toBe("—");
    expect(formatQueryRankChange({ kind: "UNCHANGED", amount: 0, rank: 1 }).label).toBe("—");
    expect(formatQueryRankChange({ kind: "IMPROVED", amount: 2, rank: 3 })).toEqual({ label: "↑ 2", direction: "up" });
    expect(formatQueryRankChange({ kind: "DECLINED", amount: 1, rank: 5 })).toEqual({ label: "↓ 1", direction: "down" });
    expect(formatQueryRankChange({ kind: "ENTERED", amount: 0, rank: 8 })).toEqual({ label: "NEW #8", direction: "up" });
    expect(formatQueryRankChange({ kind: "EXITED", amount: 50, rank: null })).toEqual({
      label: "↓ >50",
      direction: "down",
    });
    expect(formatActivity("ACTIVE")).toBe("Active");
    expect(formatActivity("LOW_ACTIVITY")).toBe("Low");
    expect(formatActivity("INACTIVE")).toBe("Inactive");
    expect(formatActivity("ARCHIVED")).toBe("Archived");
    expect(formatActivity("OTHER")).toBe("Unknown");
    expect(activityClass("ACTIVE")).toContain("emerald");
    expect(activityClass("LOW_ACTIVITY")).toContain("amber");
    expect(activityClass("ARCHIVED")).toContain("slate");
    expect(activityClass(null)).toContain("muted");
    expect(growthClass("up")).toContain("emerald");
    expect(growthClass("down")).toContain("red");
    expect(growthClass("flat")).toContain("muted");
  });

  it("formats dates times and relative labels", () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-09-23T12:00:00Z"));
    expect(formatChartAxisDate("2026-09-23")).toMatch(/Sep/);
    expect(formatSyncTime(null)).toBeNull();
    expect(formatSyncTime("2026-09-23T06:10:00Z")).toMatch(/Sep/);
    expect(formatRelativeTime(null)).toBe("—");
    expect(formatRelativeTime("2026-09-23T01:00:00Z")).toBe("today");
    expect(formatRelativeTime("2026-09-22T01:00:00Z")).toBe("1d ago");
    expect(formatRelativeTime("2026-09-10T01:00:00Z")).toBe("13d ago");
    expect(formatRelativeTime("2026-07-23T01:00:00Z")).toBe("2mo ago");
    expect(formatRelativeTime("2024-09-23T01:00:00Z")).toBe("2y ago");
    expect(formatActivityPresentation(null)).toBe("Unknown");
    expect(formatActivityPresentation("ARCHIVED", "2026-09-23T01:00:00Z")).toBe("Archived");
    expect(formatActivityPresentation("ACTIVE", "2026-09-23T01:00:00Z")).toBe("Active · today");
  });

  it("fills calendar gaps", () => {
    expect(calendarDates([])).toEqual([]);
    expect(calendarDates(["2026-09-23", "2026-09-21"])).toEqual(["2026-09-21", "2026-09-22", "2026-09-23"]);
  });
});
