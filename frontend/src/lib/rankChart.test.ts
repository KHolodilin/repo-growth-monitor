import { describe, expect, it, vi } from "vitest";
import { datesFromHistory, rankHistoryOption, rankTooltipText, recentMissedDates } from "./rankChart";

describe("rankChart", () => {
  it("describes tooltip states", () => {
    expect(rankTooltipText(undefined, 50, true)).toBe("Not collected");
    expect(rankTooltipText(undefined, 50)).toBe("no data");
    expect(rankTooltipText({ date: "2026-09-23", position: null }, 50)).toBe("Not found in Top 50");
    expect(rankTooltipText({ date: "2026-09-23", position: 4 }, 50)).toBe("#4");
  });

  it("builds option formatters and missed markers", () => {
    const option = rankHistoryOption({
      dates: ["2026-09-22", "2026-09-23"],
      legend: true,
      series: [
        {
          name: "outbox",
          limit: 50,
          highlighted: true,
          dimmed: false,
          missedDates: ["2026-09-22"],
          points: [
            { date: "2026-09-22", position: null },
            { date: "2026-09-23", position: 24 },
          ],
        },
      ],
    });
    const tooltip = option.tooltip.formatter as (params: { axisValue: string }[]) => string;
    expect(tooltip([])).toBe("");
    expect(tooltip([{ axisValue: "2026-09-23" }])).toContain("#24");
    expect(option.yAxis.axisLabel.formatter(51)).toBe(">50");
    expect(option.yAxis.axisLabel.formatter(3)).toBe("#3");
    expect(option.xAxis.axisLabel.formatter("2026-09-23")).toMatch(/Sep/);
    expect(option.series).toHaveLength(3);
  });

  it("collects dates and recent misses", () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-09-23T12:00:00Z"));
    expect(datesFromHistory([[{ date: "2026-09-21", position: 1 }]], ["2026-09-23"])).toEqual([
      "2026-09-21",
      "2026-09-22",
      "2026-09-23",
    ]);
    expect(recentMissedDates(undefined)).toEqual([]);
    expect(recentMissedDates(["2026-09-01", "2026-09-22"])).toEqual(["2026-09-22"]);
    vi.useRealTimers();
  });
});
