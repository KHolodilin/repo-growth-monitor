import { afterEach, describe, expect, it, vi } from "vitest";
import { chartYesterday, withoutIncompletePortfolioTipDay } from "./portfolioTipDay";

describe("portfolioTipDay", () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it("computes calendar yesterday in UTC", () => {
    expect(chartYesterday("2026-11-18")).toBe("2026-11-17");
    expect(chartYesterday("2026-03-01")).toBe("2026-02-28");
  });

  it("defaults chartYesterday to the system UTC date", () => {
    vi.setSystemTime(new Date("2026-11-18T12:00:00Z"));
    expect(chartYesterday()).toBe("2026-11-17");
  });

  it("drops only incomplete T−1 and keeps earlier partial days", () => {
    vi.setSystemTime(new Date("2026-11-18T12:00:00Z"));
    const points = [
      { date: "2026-11-15", views: 10, missingRepositories: 2 },
      { date: "2026-11-16", views: 11, missingRepositories: 0 },
      { date: "2026-11-17", views: 5, missingRepositories: 3 },
    ];
    expect(withoutIncompletePortfolioTipDay(points)).toEqual([
      { date: "2026-11-15", views: 10, missingRepositories: 2 },
      { date: "2026-11-16", views: 11, missingRepositories: 0 },
    ]);
  });

  it("keeps complete T−1 and days without missingRepositories", () => {
    vi.setSystemTime(new Date("2026-11-18T12:00:00Z"));
    expect(
      withoutIncompletePortfolioTipDay([
        { date: "2026-11-17", views: 9, missingRepositories: 0 },
        { date: "2026-11-16", views: 8 },
      ]),
    ).toEqual([
      { date: "2026-11-17", views: 9, missingRepositories: 0 },
      { date: "2026-11-16", views: 8 },
    ]);
  });
});
