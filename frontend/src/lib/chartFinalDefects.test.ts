import { describe, expect, it } from "vitest";
import type { GrowthEvent } from "./api";
import { presentChartOption } from "./chartPresentation";
import { rankHistoryOption } from "./rankChart";
import { SEARCH_METRICS, searchMetricOption, storedCountOption } from "./searchSeriesChart";
import { chartCsv } from "./chartExport";

const dates = ["2026-10-28", "2026-10-29", "2026-10-30", "2026-10-31"];
const events = dates.map((date, index): GrowthEvent => ({ id: index, repositoryId: 13, eventAt: `${date}T12:00:00Z`, category: "PROMOTION", type: "LINKEDIN_POST", title: `Launch ${index}`, source: "MANUAL" }));
const lines = [
  { name: "ours", tracked: true, points: dates.map((date, i) => ({ date, position: i === 2 ? null : i + 1, stars: i === 2 ? null : i * 10, forks: i === 2 ? null : i })) },
  { name: "rival", points: dates.slice(1).map((date, i) => ({ date, position: i + 2, stars: 30 + i, forks: i })) },
];
describe("final chart defect regression", () => {
  it("retains explicit dates through generic Calendar/Timeline and sparse unequal starts", () => {
    const option = { xAxis: { data: dates }, series: [{ name: "Early", data: [0, null, 2, 1] }, { name: "Late", data: [null, 2, null, 0] }] };
    const timeline = presentChartOption<Record<string, any>>(option, { axis: "timeline", events, mobile: true });
    expect(timeline.xAxis.data).toEqual(["1", "2", "3", "4"]);
    expect(timeline.eventAxisDates).toEqual(dates);
    expect(timeline.series.map((s: any) => s.data)).toEqual([[0, null, 2, 1], [2, null, 0, null]]);
    expect(timeline.series.some((s: any) => s.name === "Events")).toBe(false);
    expect(presentChartOption(option, { axis: "calendar", events, mobile: true }).xAxis.data).toEqual(dates);
    const late = presentChartOption<Record<string, any>>({ xAxis: { data: dates }, series: [{ data: [null, null, 0, 2] }] }, { axis: "timeline", events, mobile: true });
    expect(late.eventAxisDates).toEqual(dates.slice(2));
  });
  it("uses the rank frame for query/topic siblings and preserves desktop event positions", () => {
    const input = { dates, series: [{ name: "ours", limit: 50, points: lines[0].points }, { name: "rival", limit: 50, points: lines[1].points }], events };
    const topic = rankHistoryOption({ ...input, axis: "timeline" });
    expect(topic.eventAxisDates).toEqual(dates);
    expect(topic.series.at(-1)).toMatchObject({ markLine: { data: ["1", "2", "3", "4"].map(xAxis => ({ xAxis })) } });
    for (const metric of ["place", "stars", "forks"] as const) {
      const query = searchMetricOption({ metric, axis: "timeline", dates, lines, limit: 50, missedDates: [], events });
      expect(query.eventAxisDates).toEqual(dates);
      expect(query.series.at(-1)).toMatchObject({ markLine: { data: ["1", "2", "3", "4"].map(xAxis => ({ xAxis })) } });
    }
    const outside = { ...events[0], eventAt: "2026-11-01T12:00:00Z" };
    const calendarQuery = searchMetricOption({ metric: "place", dates, lines, limit: 50, missedDates: [], events: [outside] });
    expect(calendarQuery.eventAxisDates).toEqual(calendarQuery.xAxis.data);
    expect(calendarQuery.eventAxisDates).toContain("2026-11-01");
    expect(rankHistoryOption(input).series.at(-1)).toMatchObject({ markLine: { data: dates.map(xAxis => ({ xAxis })) } });
  });
  it.each(["place", "stars", "forks"] as const)("labels %s axis, tooltip and CSV without altering values", metric => {
    for (const axis of ["calendar", "timeline"] as const) {
      const option = searchMetricOption({ metric, axis, dates, lines, limit: 50, missedDates: [] });
      const label = SEARCH_METRICS[metric].label;
      expect(option.yAxis.name).toBe(label);
      expect(option.yAxis.nameLocation).toBe(metric === "place" ? "start" : "end");
      expect("inverse" in option.yAxis && option.yAxis.inverse === true).toBe(metric === "place");
      const value = axis === "timeline" ? "1" : dates[0];
      const html = option.tooltip.formatter([{ axisValue: value, seriesName: "ours", data: metric === "place" ? 1 : 0 }]);
      expect(html).toContain(`${label} ·`);
      expect(html).toContain(metric === "place" ? "#1" : ": 0");
      if (axis === "timeline") {
        expect(html).toContain(`ours (${dates[0]})`);
        const rival = option.tooltip.formatter([{ axisValue: "1", seriesName: "rival", data: 30 }]);
        expect(rival).toContain(`rival (${dates[1]})`);
        expect(option.tooltip.formatter([{ axisValue: "9", seriesName: "unknown", data: null }])).not.toContain("undefined");
      }
      expect(option.tooltip.formatter([])).toBe("");
      const csv = chartCsv(option);
      expect(csv.split("\n")[0]).toBe(`date,ours (${label}),rival (${label})`);
      expect(csv).not.toContain("undefined");
      const series = option.series[0].data;
      expect(series).toEqual(metric === "place" ? [1, 2, null, 4] : metric === "stars" ? [0, 10, null, 30] : [0, 1, null, 3]);
      if (metric === "place") {
        expect("axisLabel" in option.yAxis && option.yAxis.axisLabel.formatter(51)).toBe(">50");
        expect(option.tooltip.formatter([{ axisValue: axis === "timeline" ? "3" : dates[2], seriesName: "ours", data: null }])).toContain("Not found in Top 50");
      }
    }
    expect(presentChartOption(searchMetricOption({ metric, dates, lines, limit: 50, missedDates: [] }), { axis: "calendar", events: [], mobile: true }).grid.top).toBe(32);
  });
  it("leaves shared count charts unlabelled unless their consumer requests metric labels", () => {
    expect(storedCountOption({ dates, series: [] }).yAxis).toEqual({ type: "value" });
  });
});
