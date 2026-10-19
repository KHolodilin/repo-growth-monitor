import { describe, expect, it } from "vitest";
import type { GrowthEvent } from "./api";
import { chartDatesWithEvents, eventMarkOverlays, markLineEvents, trafficChartOption } from "./trafficChart";

const event = (date: string, title: string, id = 1): GrowthEvent => ({
  id,
  repositoryId: 13,
  eventAt: `${date}T12:00:00Z`,
  category: "PROMOTION",
  type: "LINKEDIN_POST",
  title,
  source: "MANUAL",
});

describe("trafficChart", () => {
  it("merges event dates and builds overlays", () => {
    expect(chartDatesWithEvents([{ date: "2026-09-23", views: 1, uniqueVisitors: 1, clones: 0, uniqueCloners: 0 }], [
      event("2026-09-21", "Post"),
    ])).toEqual(["2026-09-21", "2026-09-23"]);

    const overlays = eventMarkOverlays(
      ["2026-09-20", "2026-09-21", "2026-09-22", "2026-09-23"],
      [
        event("2026-09-20", "A", 1),
        event("2026-09-21", "B", 2),
        event("2026-09-22", "C", 3),
        event("2026-09-22", "D", 4),
        event("2026-09-23", "E", 5),
        event("2026-09-10", "off-axis", 6),
      ],
    );
    expect(overlays.groups).toHaveLength(4);
    expect(overlays.markLine.tooltip.formatter({})).toBe("");
    expect(overlays.markLine.tooltip.formatter({ data: {} })).toBe("");
    expect(overlays.markLine.tooltip.formatter({ data: { events: [event("2026-09-20", "A")] } })).toContain("A");
    expect(overlays.markPoint.label.formatter({})).toBe("");
    expect(overlays.markPoint.label.formatter({ data: { events: [event("2026-09-20", "A"), event("2026-09-20", "B", 2)] } })).toBe(
      "2",
    );
    expect(overlays.markPoint.label.formatter({ data: {} })).toBe("");
    expect(overlays.markPoint.label.formatter({ data: { events: [event("2026-09-20", "A")], countAsText: false } })).toBe("");
  });

  it("formats traffic tooltips and marker clicks", () => {
    const option = trafficChartOption(
      [{ date: "2026-09-23", views: 5, uniqueVisitors: null, clones: 1, uniqueCloners: 1 }],
      [event("2026-09-23", "Launch")],
    );
    const formatter = option.tooltip.formatter as (
      params: { axisValue: string; seriesName: string; data: number | null }[],
    ) => string;
    expect(formatter([])).toBe("");
    expect(
      formatter([
        { axisValue: "2026-09-23", seriesName: "Views", data: 5 },
        { axisValue: "2026-09-23", seriesName: "Events", data: null },
        { axisValue: "2026-09-23", seriesName: "Visitors", data: null },
      ]),
    ).toContain("Launch");
    expect((option.tooltip.formatter as (params: unknown) => string)("nope")).toBe("");
    expect(
      (option.tooltip.formatter as (params: { axisValue: string; seriesName: string; data: number | null | undefined }[]) => string)([
        { axisValue: "2026-09-01", seriesName: "Views", data: undefined },
      ]),
    ).not.toContain("Launch");
    const sparse = trafficChartOption(
      [{ date: "2026-09-23", views: 1, uniqueVisitors: 1, clones: 0, uniqueCloners: 0 }],
      [{ ...event("2026-09-21", "Only"), title: undefined as unknown as string }],
    );
    expect(sparse.series[0].data).toContain(null);
    expect(sparse.series[1].data).toContain(null);
    expect(sparse.series[2].data).toContain(null);
    expect(sparse.series[3].markPoint.data[0].name).toBe("Events");
    expect(option.legend).toMatchObject({ top: 0 });
    expect(option.xAxis.axisLabel.formatter("2026-09-23")).toMatch(/Sep/);

    const broken = trafficChartOption(
      [
        { date: "2026-10-15", views: 4, uniqueVisitors: 2, clones: 1, uniqueCloners: 1 },
        { date: "2026-10-17", views: 6, uniqueVisitors: 3, clones: 1, uniqueCloners: 1 },
      ],
      [],
      "7d",
      { reason: "GITHUB_DATA_MISSING", date: "2026-10-18", lastTrafficDate: "2026-10-17" },
    );
    expect(broken.xAxis.data).toContain("2026-10-16");
    expect(broken.xAxis.data).toContain("2026-10-18");
    expect(broken.series[0].data[broken.xAxis.data.indexOf("2026-10-16")]).toBeNull();
    expect(broken.series[0].connectNulls).toBe(false);
    expect(broken.series[0].markPoint?.data[0]).toMatchObject({ xAxis: "2026-10-18" });
    const gapFormatter = broken.tooltip.formatter as (params: { axisValue: string; seriesName: string; data: number | null }[]) => string;
    expect(gapFormatter([{ axisValue: "2026-10-18", seriesName: "Views", data: null }])).toBe(
      "GitHub traffic data is missing for Oct 18 (T-1).",
    );
    const kept = trafficChartOption(
      [{ date: "2026-10-18", views: 0, uniqueVisitors: 0, clones: 0, uniqueCloners: 0 }],
      [],
      "7d",
      { reason: "COLLECTION_FAILED", date: "2026-10-19", lastTrafficDate: null },
    );
    expect(kept.series[0].markPoint).toBeUndefined();
    expect(kept.series[0].data).toEqual([0]);
    expect(trafficChartOption([], [], "7d", {
      reason: "COLLECTION_NOT_EXECUTED",
      date: "2026-10-19",
      lastTrafficDate: null,
    }).xAxis.data).toEqual(["2026-10-18"]);
    expect(markLineEvents({ componentType: "series" })).toEqual([]);
    expect(markLineEvents({ componentType: "markLine", data: { events: [event("2026-09-23", "Launch")] } })).toHaveLength(
      1,
    );
    expect(markLineEvents({ componentType: "markPoint" })).toEqual([]);
  });

  it("keeps text on two distant days", () => {
    const overlays = eventMarkOverlays(
      ["2026-01-01", "2026-06-01"],
      [event("2026-01-01", "A", 1), event("2026-06-01", "B", 2)],
    );
    expect(overlays.markLine.data.map((item) => item.label)).toEqual([
      { show: true, position: "end", distance: 18 },
      { show: true, position: "end", distance: 18 },
    ]);
  });

  it("writes the count when a neighbouring label would cross the one on its left", () => {
    const overlays = eventMarkOverlays(days(30), [
      event("2026-01-01", "A", 1),
      event("2026-01-02", "B", 2),
    ]);
    expect(overlays.markLine.data.map((item) => item.name)).toEqual(["LinkedIn post", "1"]);
    expect(overlays.markPoint.label.formatter({ data: overlays.markPoint.data[1] })).toBe("");
  });

  it("writes the count when crowded labels have no room for the full text", () => {
    const overlays = eventMarkOverlays(days(60), [
      event("2026-01-01", "A", 1),
      event("2026-01-02", "B", 2),
      event("2026-01-03", "C", 3),
      event("2026-01-04", "D", 4),
      event("2026-01-05", "E", 5),
    ]);
    expect(overlays.markLine.data.map((item) => item.name)).toEqual(["LinkedIn post", "1", "1", "1", "1"]);
    expect(overlays.markPoint.label.formatter({ data: overlays.markPoint.data[4] })).toBe("");
  });

  it("writes counts for long labels that would cross a neighbour on a 30 day axis", () => {
    const github = (date: string, id: number): GrowthEvent => ({
      ...event(date, "GitHub", id),
      type: "EXTERNAL_PR_OPENED",
      source: "GITHUB",
    });
    const overlays = eventMarkOverlays(daysFrom("2026-09-07", 30), [
      github("2026-09-28", 1),
      github("2026-10-01", 2),
      github("2026-10-01", 3),
      github("2026-10-01", 4),
      github("2026-10-04", 5),
      github("2026-10-05", 6),
      github("2026-10-05", 7),
    ]);
    const names = new Map(overlays.markLine.data.map((item) => [item.xAxis, item.name]));
    expect(names.get("2026-09-28")).toBe("External pull request opened");
    expect(names.get("2026-10-01")).toBe("3");
    expect(names.get("2026-10-04")).toBe("1");
    expect(names.get("2026-10-05")).toBe("2");
    const pin = (date: string) => overlays.markPoint.label.formatter({ data: overlays.markPoint.data.find((item) => item.xAxis === date) });
    expect(pin("2026-10-01")).toBe("");
    expect(pin("2026-10-05")).toBe("");
  });

  it("keeps count labels on one line across a dense range", () => {
    const overlays = eventMarkOverlays(
      daysFrom("2026-08-01", 90),
      [
        event("2026-08-01", "A", 1),
        event("2026-08-02", "B", 2),
        event("2026-08-02", "C", 3),
        event("2026-08-03", "D", 4),
      ],
      1,
      "90d",
    );
    expect(overlays.markLine.data.map((item) => item.label)).toEqual([
      { show: true, position: "end", distance: 18 },
      { show: true, position: "end", distance: 18 },
      { show: true, position: "end", distance: 18 },
    ]);
  });

  it("writes the event count above the line on a long range", () => {
    for (const period of ["90d", "1y", "all"]) {
      const overlays = eventMarkOverlays(
        ["2026-09-01", "2026-09-02"],
        [event("2026-09-01", "A", 1), event("2026-09-02", "B", 2), event("2026-09-02", "C", 3)],
        1,
        period,
      );
      expect(overlays.markLine.data.map((item) => item.name)).toEqual(["1", "2"]);
      expect(overlays.markLine.data.map((item) => item.label)).toEqual([
        { show: true, position: "end", distance: 18 },
        { show: true, position: "end", distance: 18 },
      ]);
      expect(overlays.markPoint.label.formatter({ data: overlays.markPoint.data[0] })).toBe("");
    }
  });
});

function days(count: number) {
  return daysFrom("2026-01-01", count);
}

function daysFrom(start: string, count: number) {
  const [year, month, day] = start.split("-").map(Number);
  return Array.from({ length: count }, (_, index) => {
    const date = new Date(Date.UTC(year, month - 1, day + index));
    return date.toISOString().slice(0, 10);
  });
}
