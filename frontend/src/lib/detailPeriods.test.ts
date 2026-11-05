import { describe, expect, it } from "vitest";
import { chartCsv } from "./chartExport";
import { presentChartOption } from "./chartPresentation";
import { queryDetailsChart, searchMetricOption, storedCountOption } from "./searchSeriesChart";
import type { GrowthEvent } from "./api";
const today = "2026-11-05";
const dates = ["2026-09-01", "2026-10-29", "2026-11-03", "2026-11-04", "2026-11-05"];
const points = dates.map((date, index) => ({ date, position: index + 1, stars: index === 3 ? 0 : index * 10, forks: index }));
const event = (date: string): GrowthEvent => ({ id: 1, repositoryId: 13, eventAt: `${date}T12:00:00Z`, category: "PROMOTION", type: "LINKEDIN_POST", title: date, source: "MANUAL" });
describe("detail chart periods", () => {
  it.each(["place", "stars", "forks"] as const)("filters %s before timeline framing and export", metric => {
    for (const axis of ["calendar", "timeline"] as const) {
      const option = searchMetricOption({ metric, axis, today, period: "1d", dates, lines: [{ name: "ours", tracked: true, points }], missedDates: [], limit: 50, events: [event("2026-10-29"), event("2026-11-04"), event("2026-11-05")] });
      expect(option.xAxis.data).toEqual([axis === "calendar" ? "2026-11-04" : "1"]);
      expect(option.eventAxisDates).toEqual(["2026-11-04"]);
      expect(option.series[0].data).toEqual([metric === "stars" ? 0 : metric === "place" ? 4 : 3]);
      expect(option.series.at(-1)).toMatchObject({ name: "Events", markLine: { data: [{ xAxis: axis === "calendar" ? "2026-11-04" : "1" }] } });
      const csv = chartCsv(option); expect(csv).not.toContain("2026-09-01"); expect(csv.split("\n")).toHaveLength(2);
    }
  });
  it("uses calendar windows, keeps missing values and restores all history", () => {
    const input = { metric: "stars" as const, today, dates, lines: [{ name: "ours", tracked: true, points }], missedDates: [], limit: 50 };
    const week = searchMetricOption({ ...input, period: "7d" });
    expect(week.xAxis.data[0]).toBe("2026-10-30"); expect(week.xAxis.data.at(-1)).toBe(today);
    expect(week.series[0].data).toEqual([null,null,null,null,20,0,40]);
    expect(searchMetricOption({ ...input, period: "30d" }).xAxis.data[0]).toBe("2026-10-07");
    expect(searchMetricOption({ ...input, period: "90d" }).xAxis.data).toHaveLength(90);
    expect(searchMetricOption({ ...input, period: "1y" }).xAxis.data).toHaveLength(365);
    const all = searchMetricOption({ ...input, period: "all" });
    expect(all.xAxis.data).toEqual(dates);
    expect(all.series[0]).toMatchObject({ itemStyle: (week.series[0] as { itemStyle: unknown }).itemStyle });
  });
  it("passes the period through the actual query details builder while retaining competitors", () => {
    const chart = queryDetailsChart({ today, period: "1d", metric: "stars", history: { points, missedDates: [], query: { resultLimit: 50 } }, repo: { githubId: 99, fullName: "ours" }, rows: [{ result: { githubRepositoryId: 1, fullName: "rival" } }], selected: [1], series: [99,1].map(githubRepositoryId => ({ githubRepositoryId, fullName: githubRepositoryId === 99 ? "ours" : "rival", points })) })!;
    expect(chart.series).toHaveLength(2); expect(chart.option.xAxis.data).toEqual(["2026-11-04"]);
    expect(chart.option.series.filter(item => item.type === "line").map(item => item.data)).toEqual([[0],[0]]);
  });
  it("clips competitor counts without inventing zero, including sparse/empty payloads", () => {
    const input = { dates, series: [{ name: "Stars", data: [1,2,null,0,5] }] };
    expect(storedCountOption({ ...input, today, period: "1d" }).series[0].data).toEqual([0]);
    const week = storedCountOption({ ...input, today, period: "7d" });
    expect(week.series[0].data).toEqual([null,null,null,null,null,0,5]);
    expect(storedCountOption({ dates: ["2026-11-04"], series: [{ name: "Stars", data: [] }], today, period: "1d" }).series[0].data).toEqual([null]);
    expect(storedCountOption(input).xAxis.data).toEqual(dates);
    expect(presentChartOption(week, { axis: "timeline", events: [] }).xAxis.data).toEqual(["1","2"]);
  });
});
