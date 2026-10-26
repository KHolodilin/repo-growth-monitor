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
    expect(option.xAxis.axisLabel).not.toHaveProperty("alignMinLabel");
    expect(option.xAxis.axisLabel).not.toHaveProperty("alignMaxLabel");
    expect(option.legend).toMatchObject({ top: 0 });
    expect(option.series).toHaveLength(3);
    const withoutMisses = rankHistoryOption({
      dates: ["2026-09-23"],
      series: [{ name: "plain", limit: 50, points: [{ date: "2026-09-23", position: 2 }] }],
    });
    const plainTooltip = withoutMisses.tooltip.formatter as (params: { axisValue: string }[]) => string;
    expect(plainTooltip([{ axisValue: "2026-09-23" }])).toContain("#2");
    const marked = rankHistoryOption({
      dates: ["2026-09-23"],
      events: [
        {
          id: 1,
          repositoryId: 13,
          eventAt: "2026-09-22T12:00:00Z",
          category: "PROMOTION",
          type: "LINKEDIN_POST",
          title: "LinkedIn launch",
          source: "MANUAL",
        },
      ],
      series: [{ name: "outbox", limit: 50, points: [{ date: "2026-09-23", position: 2 }] }],
    });
    expect(marked.series).toHaveLength(4);
    expect(marked.grid.top).toBe(64);
    const eventLine = marked.series[3] as {
      markLine: { data: { label: { position: string } }[] };
      markPoint?: unknown;
    };
    expect(eventLine.markLine.data[0].label.position).toBe("start");
    expect(eventLine.markPoint).toBeUndefined();
    expect(marked.xAxis.data).toEqual(["2026-09-22", "2026-09-23"]);
    const markedTooltip = marked.tooltip.formatter as (params: { axisValue: string }[]) => string;
    const hint = markedTooltip([{ axisValue: "2026-09-22" }]);
    expect(hint).toContain("LinkedIn launch");
    expect(hint).not.toContain("Before");
  });

  it("keeps the axis inside the selected period and drops older events", () => {
    const option = rankHistoryOption({
      dates: ["2026-08-01", "2026-10-22"],
      period: "7d",
      today: "2026-10-22",
      events: [
        {
          id: 1,
          repositoryId: 13,
          eventAt: "2026-08-01T12:00:00Z",
          category: "PROMOTION",
          type: "LINKEDIN_POST",
          title: "Old launch",
          source: "MANUAL",
        },
      ],
      series: [
        {
          name: "outbox",
          limit: 50,
          points: [
            { date: "2026-08-01", position: 4 },
            { date: "2026-10-22", position: 2 },
          ],
        },
      ],
    });
    expect(option.xAxis.data).toEqual([
      "2026-10-16",
      "2026-10-17",
      "2026-10-18",
      "2026-10-19",
      "2026-10-20",
      "2026-10-21",
      "2026-10-22",
    ]);
    const line = option.series[0] as { data: (number | null)[] };
    expect(line.data[0]).toBeNull();
    expect(line.data[6]).toBe(2);
    expect(option.series).toHaveLength(3);
  });

  it("starts a timeline at the first stored point and keeps the event on that day", () => {
    const option = rankHistoryOption({
      dates: ["2026-09-21", "2026-09-22", "2026-09-23"],
      axis: "timeline",
      events: [
        {
          id: 1,
          repositoryId: 13,
          eventAt: "2026-09-23T12:00:00Z",
          category: "PROMOTION",
          type: "LINKEDIN_POST",
          title: "Launch",
          source: "MANUAL",
        },
      ],
      series: [
        {
          name: "outbox",
          limit: 50,
          missedDates: ["2026-09-22"],
          points: [
            { date: "2026-09-22", position: null },
            { date: "2026-09-23", position: 4 },
          ],
        },
      ],
    });
    expect(option.xAxis.data).toEqual(["1", "2"]);
    expect(option.xAxis.axisLabel.formatter("2")).toBe("Day 2");
    const line = option.series[0] as { data: (number | null)[] };
    expect(line.data).toEqual([null, 4]);
    const tooltip = option.tooltip.formatter as (params: { axisValue: string }[]) => string;
    expect(tooltip([{ axisValue: "1" }])).toContain("Not collected");
    expect(tooltip([{ axisValue: "2" }])).toContain("Day 2");
    expect(tooltip([{ axisValue: "2" }])).toContain("Launch");
    expect(rankHistoryOption({ dates: ["2026-09-23"], axis: "timeline", series: [] }).xAxis.data).toEqual([]);
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
