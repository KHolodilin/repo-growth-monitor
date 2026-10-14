import { describe, expect, it } from "vitest";
import { queryChartLines, searchMetricOption, searchSeriesFromPayload } from "./searchSeriesChart";

const lines = queryChartLines({
  snapshotDates: ["2026-09-22", "2026-09-23"],
  ours: {
    name: "acme/app",
    rank: [
      { date: "2026-09-22", position: 2 },
      { date: "2026-09-23", position: null },
    ],
    stored: [{ date: "2026-09-22", stars: 20, forks: 4 }],
  },
  neighbors: [
    {
      name: "other/repo",
      stored: [{ date: "2026-09-23", position: 1, stars: 11, forks: 3 }],
    },
  ],
});

describe("searchSeriesChart", () => {
  it("reads a series payload and ignores anything else", () => {
    expect(searchSeriesFromPayload(null)).toEqual([]);
    expect(searchSeriesFromPayload([])).toEqual([]);
    expect(searchSeriesFromPayload({ series: "nope" })).toEqual([]);
    expect(searchSeriesFromPayload({ series: [{ githubRepositoryId: 1 }] })).toEqual([{ githubRepositoryId: 1 }]);
  });

  it("keeps a gap where a neighbor was outside the result limit", () => {
    expect(lines[0].points).toEqual([
      { date: "2026-09-22", position: 2, stars: 20, forks: 4 },
      { date: "2026-09-23", position: null, stars: null, forks: null },
    ]);
    expect(lines[1].points).toEqual([
      { date: "2026-09-22", position: null, stars: null, forks: null },
      { date: "2026-09-23", position: 1, stars: 11, forks: 3 },
    ]);
  });

  it("draws place on a rank axis and stars or forks as counts", () => {
    const place = searchMetricOption({
      metric: "place",
      dates: ["2026-09-22", "2026-09-23"],
      missedDates: ["2026-09-21"],
      limit: 50,
      lines,
    });
    expect(place.yAxis.inverse).toBe(true);
    expect(place.legend).toMatchObject({ top: 0 });
    const placeLine = place.series[0] as { data: (number | null)[] };
    expect(placeLine.data).toEqual([2, null]);
    const neighborLine = place.series[3] as { data: (number | null)[] };
    expect(neighborLine.data).toEqual([null, 1]);

    const stars = searchMetricOption({
      metric: "stars",
      dates: ["2026-09-23"],
      missedDates: [],
      limit: 50,
      lines,
    });
    expect(stars.yAxis).toEqual({ type: "value" });
    expect(stars.xAxis.boundaryGap).toBe(true);
    expect(stars.series[0].data).toEqual([null]);
    expect(stars.series[1].data).toEqual([11]);
    const tooltip = stars.tooltip.formatter as (params: { axisValue: string; seriesName: string; data: number | null }[]) => string;
    expect(tooltip([])).toBe("");
    expect(tooltip("nope" as never)).toBe("");
    expect(tooltip([{ axisValue: "2026-09-23", seriesName: "other/repo", data: undefined as unknown as null }])).toContain("—");
    expect(tooltip([{ axisValue: "2026-09-23", seriesName: "other/repo", data: null }])).toContain("—");
    expect(tooltip([{ axisValue: "2026-09-23", seriesName: "other/repo", data: 11 }])).toContain("11");
    expect(stars.xAxis.axisLabel.formatter("2026-09-23")).toMatch(/Sep/);

    const forks = searchMetricOption({
      metric: "forks",
      dates: ["2026-09-22", "2026-09-23"],
      missedDates: [],
      limit: 50,
      lines,
    });
    expect(forks.xAxis.boundaryGap).toBe(false);
    expect(forks.series.map((item) => item.data)).toEqual([
      [4, null],
      [null, 3],
    ]);
    expect(searchMetricOption({ metric: "place", dates: [], missedDates: [], limit: 50, lines: [] }).legend).toBeUndefined();
  });
});
