import { describe, expect, it } from "vitest";
import { queryChartLines, queryDetailsChart, searchMetricOption, searchSeriesFromPayload, storedCountOption } from "./searchSeriesChart";

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

  it("drops our row and repos that left the result page", () => {
    const history = {
      points: [{ date: "2026-09-23", position: 4 }],
      missedDates: [] as string[],
      query: { resultLimit: 50 },
    };
    const repo = { githubId: 99, fullName: "acme/app" };
    const rows = [
      { result: { githubRepositoryId: 1, fullName: "other/repo" } },
      { result: { githubRepositoryId: 7, fullName: "bare/repo" } },
      { result: { githubRepositoryId: 99, fullName: "acme/app" } },
    ];

    expect(queryDetailsChart({ history: null, repo, rows, series: [], selected: [], metric: "place" })).toBeNull();
    expect(queryDetailsChart({ history, repo: null, rows, series: [], selected: [], metric: "place" })).toBeNull();

    const chart = queryDetailsChart({
      history,
      repo,
      rows,
      series: [
        {
          githubRepositoryId: 1,
          fullName: "other/repo",
          points: [{ date: "2026-09-23", position: 1, stars: 10, forks: 2 }],
        },
      ],
      selected: [99, 1, 7, 404],
      metric: "stars",
    });
    expect(chart?.series).toEqual([
      { key: "99", name: "acme/app" },
      { key: "1", name: "other/repo" },
      { key: "7", name: "bare/repo" },
    ]);
    const drawn = chart?.option.series as { data: (number | null)[] }[];
    expect(drawn.map((item) => item.data)).toEqual([[null], [10], [null]]);

    const storedOurs = queryDetailsChart({
      history,
      repo,
      rows: null,
      series: [
        {
          githubRepositoryId: 99,
          fullName: "acme/app",
          points: [{ date: "2026-09-23", position: 4, stars: 21, forks: 16 }],
        },
      ],
      selected: [1],
      metric: "forks",
    });
    expect(storedOurs?.series).toEqual([{ key: "99", name: "acme/app" }]);
    expect((storedOurs?.option.series as { data: (number | null)[] }[]).map((item) => item.data)).toEqual([[16]]);
  });

  it("leaves an empty point where the neighbor was outside the result limit", () => {
    const option = storedCountOption({
      dates: ["2026-09-22", "2026-09-23"],
      series: [
        { name: "Stars", data: [10, null] },
        { name: "Forks", data: [1, null] },
      ],
    });
    expect(option.series.map((item) => item.data)).toEqual([
      [10, null],
      [1, null],
    ]);
    expect(option.series.every((item) => item.connectNulls === false)).toBe(true);
  });

  it("starts each repository at day 1 and keeps a later gap empty", () => {
    const built = queryChartLines({
      snapshotDates: ["2026-09-22", "2026-09-23", "2026-09-24"],
      ours: {
        name: "acme/app",
        rank: [
          { date: "2026-09-22", position: 4 },
          { date: "2026-09-23", position: null },
          { date: "2026-09-24", position: 2 },
        ],
        stored: [
          { date: "2026-09-22", stars: 20, forks: 4 },
          { date: "2026-09-24", stars: 22, forks: 5 },
        ],
      },
      neighbors: [
        {
          name: "other/repo",
          stored: [
            { date: "2026-09-23", position: 1, stars: 11, forks: 3 },
            { date: "2026-09-24", position: 3, stars: 12, forks: 4 },
          ],
        },
      ],
    });
    const place = searchMetricOption({
      metric: "place",
      axis: "timeline",
      dates: ["2026-09-21", "2026-09-22", "2026-09-23", "2026-09-24"],
      missedDates: ["2026-09-21"],
      limit: 50,
      lines: built,
    });
    expect(place.xAxis.data).toEqual(["1", "2", "3"]);
    expect(place.xAxis.axisLabel.formatter("2")).toBe("Day 2");
    const series = place.series as { data: (number | null)[] }[];
    expect(series[0].data).toEqual([4, null, 2]);
    expect(series[1].data[1]).toBe(51);
    expect(series[3].data).toEqual([1, 3, null]);

    const stars = searchMetricOption({
      metric: "stars",
      axis: "timeline",
      dates: ["2026-09-22", "2026-09-23", "2026-09-24"],
      missedDates: [],
      limit: 50,
      lines: built,
    });
    expect(stars.series.map((item) => item.data)).toEqual([
      [20, null, 22],
      [11, 12, null],
    ]);
    const tooltip = stars.tooltip.formatter as (params: { axisValue: string; seriesName: string; data: number | null }[]) => string;
    expect(tooltip([{ axisValue: "1", seriesName: "acme/app", data: 20 }])).toContain("Day 1");
  });

  it("does not start a timeline day for a repository with no snapshot", () => {
    const built = queryChartLines({
      snapshotDates: ["2026-09-22"],
      ours: {
        name: "acme/app",
        rank: [{ date: "2026-09-22", position: 2 }],
        stored: [{ date: "2026-09-22", stars: 20, forks: 4 }],
      },
      neighbors: [{ name: "ghost/repo", stored: [] }],
    });
    const place = searchMetricOption({
      metric: "place",
      axis: "timeline",
      dates: ["2026-09-22"],
      missedDates: [],
      limit: 50,
      lines: built,
    });
    expect(place.xAxis.data).toEqual(["1"]);
    const series = place.series as { data: (number | null)[] }[];
    expect(series[0].data).toEqual([2]);
    expect(series[2].data).toEqual([null]);
  });

  it("keeps a timeline event on its own day without rewriting the event time", () => {
    const event = {
      id: 1,
      repositoryId: 13,
      eventAt: "2026-09-23T00:00:00Z",
      category: "RELEASE",
      type: "RELEASE_PUBLISHED",
      title: "v1",
      source: "GITHUB" as const,
    };
    const lines = [{
      name: "acme/app",
      points: [
        { date: "2026-09-22", position: 2, stars: 4, forks: 1 },
        { date: "2026-09-23", position: 1, stars: 5, forks: 1 },
      ],
    }];
    const place = searchMetricOption({
      metric: "place",
      axis: "timeline",
      dates: ["2026-09-22", "2026-09-23"],
      missedDates: [],
      limit: 50,
      lines,
      events: [event],
    });
    const markers = place.series.find((item) => item.name === "Events") as {
      markLine: { data: { xAxis: string; events: { title: string; eventAt: string }[] }[] };
    };
    expect(markers.markLine.data[0].xAxis).toBe("2");
    expect(markers.markLine.data[0].events[0].eventAt).toBe("2026-09-23T00:00:00Z");
    const stars = searchMetricOption({
      metric: "stars",
      axis: "timeline",
      dates: ["2026-09-22", "2026-09-23"],
      missedDates: [],
      limit: 50,
      lines,
      events: [event],
    });
    const tooltip = stars.tooltip.formatter as (params: { axisValue: string; seriesName: string; data: number | null }[]) => string;
    const text = tooltip([
      { axisValue: "2", seriesName: "Events", data: null },
      { axisValue: "2", seriesName: "acme/app", data: 5 },
    ]);
    expect(text).toContain("Day 2");
    expect(text).toContain("acme/app");
    expect(text).not.toContain(">Events<");
  });
});
