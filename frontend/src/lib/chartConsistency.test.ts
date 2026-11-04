import { afterEach, describe, expect, it, vi } from "vitest";
import type { SnapshotHistory, SnapshotHistoryCell } from "./api";
import { snapshotHistory } from "../test/fixtures";
import { referrerLineColor } from "../components/ReferrerSourceIcon";
import { snapshotChartOption } from "./snapshotChart";
import { chartCsv, chartTable, downloadChartPng } from "./chartExport";
import { presentChartOption } from "./chartPresentation";
import { dashboardTrafficOption } from "./dashboardChart";
import { trafficChartOption } from "./trafficChart";
import { statsChartOption } from "./statsChart";
import { rankHistoryOption } from "./rankChart";
import { storedCountOption } from "./searchSeriesChart";
import { lineStyle, metricColor, prepareSeriesStyles, seriesStyle } from "./seriesStyle";
const dates = ["2026-10-28", "2026-10-29", "2026-10-30", "2026-10-31"];
function history(values: (number | null)[], kind: "REFERRERS" | "PATHS" = "REFERRERS"): SnapshotHistory {
  return { ...snapshotHistory, kind, dates: dates.slice(0, values.length), rows: [{ key: kind === "REFERRERS" ? "github.com" : "/owner/repo/issues", cells: values.map((value, i): SnapshotHistoryCell => ({ date: dates[i], visitors: value, views: value === null ? null : value * 3, visitorsDelta: -999, viewsDelta: 999, firstSeen: i === 0 })) }] };
}
function colorOf(series: unknown, name: string) {
  return (series as { name: string; itemStyle?: { color?: string } }[]).find(item => item.name === name)?.itemStyle?.color;
}
afterEach(() => vi.restoreAllMocks());
describe("chart consistency and snapshot semantics", () => {
  it.each(["REFERRERS", "PATHS"] as const)("plots rises, falls, missing observations and zero exactly for %s", kind => {
    const data = history([28, 20, null, 0], kind); const key = data.rows[0].key;
    for (const metric of ["VISITORS", "VIEWS"] as const) {
      const option = snapshotChartOption({ history: data, metric, selected: [key] });
      expect(option.series[0].data).toEqual(metric === "VISITORS" ? [28, 20, null, 0] : [84, 60, null, 0]);
      expect(option.series[0].connectNulls).toBe(true);
      const tooltip = option.tooltip.formatter([{ axisValue: dates[2], seriesName: key }]);
      expect(tooltip).toContain("No observation"); expect(tooltip).not.toContain("999");
      expect(option.tooltip.formatter([{ axisValue: dates[1], seriesName: key }])).toContain(metric === "VISITORS" ? "20 Visitors" : "60 Views");
      const csv = chartCsv(option); expect(csv).toContain("snapshot rolling window"); expect(csv).toContain(`${dates[2]},,`); expect(csv).toContain(`${dates[3]},0,`);
      expect(option.tooltip.formatter([])).toBe("");
    }
  });
  it("keeps snapshot dates, values, missing cells and independent series alignment in Timeline/CSV", () => {
    const data = history([28, null, 20]); data.rows.push({ key: "new.example", cells: [{ ...data.rows[0].cells[2], visitors: 5 }] });
    const option = snapshotChartOption({ history: data, metric: "VISITORS", selected: ["github.com", "new.example"] });
    const timeline = presentChartOption(option, { axis: "timeline", events: [] });
    expect(timeline.series[0].data).toEqual([28, null, 20]); expect(timeline.series[1].data).toEqual([null, null, 5]);
    expect(timeline.xAxis.data).toEqual(["1", "2", "3"]);
    expect(timeline.tooltip.formatter([{ axisValue: "3", seriesName: "new.example" }])).toContain("2026-10-30");
    expect(chartCsv(timeline)).toBe(chartCsv(option));
    expect(chartTable(timeline).dates).toEqual(data.dates);
  });
  it("exports partial Other aggregation honestly and never invents zero from all-missing inputs", () => {
    const data = history([28, 20]); data.rows.push({ key: "missing.example", cells: data.rows[0].cells.map(cell => ({ ...cell, visitors: null, views: null })) });
    const option = snapshotChartOption({ history: data, metric: "VISITORS", selected: [] });
    expect(option.series[0].data).toEqual([28, 20]);
    expect(option.tooltip.formatter([{ axisValue: dates[0], seriesName: "Other" }])).toContain("partial: 1");
    expect(chartCsv(option)).toContain("Other observation completeness"); expect(chartCsv(option)).toContain("partial: 1 unavailable");
    data.rows[0].cells = data.rows[0].cells.map(cell => ({ ...cell, visitors: null }));
    expect(snapshotChartOption({ history: data, metric: "VISITORS", selected: [] }).series[0].data).toEqual([null, null]);
    expect(snapshotChartOption({ history: history([0]), metric: "VISITORS", selected: [] }).series[0].data).toEqual([0]);
  });
  it("matches both clone measures across chart levels, CSV and fixed metric styles", () => {
    const portfolio = dashboardTrafficOption([{ date: dates[0], views: 10, visitors: 4, clones: 17, uniqueCloners: 13 }]);
    const repo = trafficChartOption([{ date: dates[0], views: 10, uniqueVisitors: 4, clones: 17, uniqueCloners: 13 }], []);
    const names = ["Views", "Visitors", "Clones", "Unique Cloners"];
    expect(portfolio.series.map(item => item.name)).toEqual(names); expect(repo.series.filter(item => item.name !== "Events").map(item => item.name)).toEqual(names);
    for (const name of names) {
      const a = portfolio.series.find(item => item.name === name)!; const b = repo.series.find(item => item.name === name)!;
      expect(a.color).toBe(colorOf(repo.series, name)); expect(a.data).toEqual(b.data);
    }
    expect(portfolio.series[2].data).not.toEqual(portfolio.series[3].data);
    expect(chartCsv(portfolio)).toContain(",17,13"); expect(chartCsv(repo)).toContain(",17,13");
    expect(metricColor("Clones")).not.toBe(metricColor("Unique Cloners"));
    const stats = statsChartOption([{ date: dates[0], stars: 2, watchers: 1, forks: 3, contributors: 4 }], { contributorsLabel: "Unique Contributors" });
    const competitor = storedCountOption({ dates: [dates[0]], series: [{ name: "Stars", data: [2] }, { name: "Forks", data: [3] }] });
    expect(stats.series[0].itemStyle.color).toBe(colorOf(competitor.series, "Stars"));
    expect(stats.series[3].itemStyle.color).toBe(metricColor("Contributors"));
    expect(metricColor("Unique Visitors")).toBe(metricColor("Visitors"));
  });
  it("keeps dynamic domain identities through reorder, hiding and a set larger than the useful palette", () => {
    const keys = ["topic:grafana-prometheus", "topic:transactional-outbox-pattern", ...Array.from({ length: 180 }, (_, i) => `topic:collision-${i}`)];
    prepareSeriesStyles(keys); const before = new Map(keys.map(key => [key, seriesStyle(key)]));
    expect(new Set([...before.values()].map(style => `${style.color}/${style.line}/${style.symbol}`)).size).toBe(keys.length);
    expect(new Set([...before.values()].map(style => style.color)).size).toBe(keys.length);
    const build = (ids: string[]) => rankHistoryOption({ dates: [dates[0]], legend: true, series: ids.map(key => ({ key, name: key, limit: 50, points: [{ date: dates[0], position: 2 }] })) });
    const a = build(keys.slice(0, 5)); const b = build(keys.slice(0, 5).reverse());
    for (const key of keys.slice(0, 5)) expect(colorOf(a.series, key)).toBe(colorOf(b.series, key));
    expect(colorOf(build([keys[0]]).series, keys[0])).toBe(before.get(keys[0])!.color);
    expect(lineStyle(keys[0]).itemStyle.color).toBe(before.get(keys[0])!.color);
  });
  it("temporarily includes chart identity and legend in PNG and restores interactive options even on failure", () => {
    vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {});
    vi.spyOn(URL, "createObjectURL").mockReturnValue("blob:png");
    vi.spyOn(URL, "revokeObjectURL").mockImplementation(() => {});
    const original = { legend: { show: false } }; const setOption = vi.fn(); const resize = vi.fn();
    const getDataURL = vi.fn(() => "data:image/png;base64,AAAA");
    const view = { getEchartsInstance: () => ({ getOption: () => original, setOption, getDataURL, resize, getWidth: () => 280, getHeight: () => 220 }) };
    downloadChartPng(view, "history.png", "Referrer Snapshot History");
    expect(setOption.mock.calls[0][0]).toMatchObject({ legend: { show: true }, title: { text: "Referrer Snapshot History" } });
    expect(setOption.mock.calls.at(-1)).toEqual([original, true]);
    expect(resize.mock.calls[0]).toEqual([{ width: 760, height: 400, silent: true }]);
    expect(resize.mock.calls.at(-1)).toEqual([{ width: 280, height: 220, silent: true }]);
    getDataURL.mockImplementation(() => { throw new Error("export failed"); });
    expect(() => downloadChartPng(view, "history.png", "History")).toThrow("export failed");
    expect(setOption.mock.calls.at(-1)).toEqual([original, true]);
  });
  it("keeps alternate table labels and empty axis/data cells truthful", () => {
    expect(chartTable({ xAxis: [{ data: [null, "2026-11-01"] }], series: [{ name: "x", type: "line", data: [] }], exportLabels: { x: "Recorded X" } })).toEqual({ dates: ["", "2026-11-01"], columns: [{ name: "Recorded X", values: ["", ""] }] });
    expect(referrerLineColor("Other", 0)).toBe(seriesStyle("Other").color);
  });

});
