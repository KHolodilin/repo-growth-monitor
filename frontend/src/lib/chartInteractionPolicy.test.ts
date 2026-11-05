import { describe, expect, it } from "vitest";
import { presentChartOption, withCollectionWarning } from "./chartPresentation";
import { rankHistoryOption } from "./rankChart";
import { trafficChartOption } from "./trafficChart";
import { dashboardTrafficOption } from "./dashboardChart";
import { statsChartOption } from "./statsChart";
import { storedCountOption } from "./searchSeriesChart";
import { snapshotChartOption } from "./snapshotChart";
import type { SnapshotHistory } from "./api";
const today = "2026-11-05", yesterday = "2026-11-04";
const mode = { axis: "calendar" as const, events: [], mobile: true };
const rank = (points: { date: string; position: number | null }[] = [{ date: "2026-11-03", position: 1 }], missedDates = [yesterday], expected = true) => rankHistoryOption({ dates: ["2026-11-03", yesterday], series: [{ name: "ours", points, missedDates, expected, limit: 50 }] });
const marker = (option: any) => option.series.find((item: any) => item.name === "Collection warning");
describe("shared observation and interaction policy", () => {
  it("removes missed dots but retains observed not-found ranks and connects real observations", () => {
    const option = rank([{ date: "2026-11-03", position: 1 }, { date: "2026-11-05", position: null }]);
    expect(option.series).toHaveLength(2);
    expect(rank([{ date: yesterday, position: null }], [yesterday]).series[1].data).toEqual([null, null]);
    expect(rank([{ date: yesterday, position: null }], []).series[1].data).toEqual([null, 51]);
    expect(option.series[0]).toMatchObject({ connectNulls: true, data: [1, null] });
    expect(option.series.every(item => item.type !== "scatter" || ("symbol" in item && item.symbol === "diamond"))).toBe(true);
    const warning = marker(withCollectionWarning(option, today));
    expect(warning.markPoint.data[0]).toEqual({ xAxis: yesterday, yAxis: 1 });
    expect(warning.markPoint.label.formatter).toBe("!");
    const twice = withCollectionWarning(withCollectionWarning(option, today), today);
    expect(twice.series?.filter(item => item.name === "Collection warning")).toHaveLength(1);
  });
  it("does not invent a warning before tracking, for disabled collection or observed null rank/zero", () => {
    for (const option of [rank([], [], true), rank(undefined, undefined, false), rank([{ date: yesterday, position: null }], []), { xAxis: { data: [yesterday] }, collectionDates: [yesterday], series: [{ name: "Stars", type: "line", data: [0] }] }]) {
      expect(marker(withCollectionWarning(option, today))).toBeUndefined();
    }
    expect(withCollectionWarning({ xAxis: [], series: [] }, today)).toEqual({ xAxis: [], series: [] });
  });
  it("keeps an explicit failure reason and a metric-independent warning through timeline framing", () => {
    const option: any = withCollectionWarning({ xAxis: { data: ["2026-11-03"] }, collectionDates: ["2026-11-03"], collectionWarning: { date: yesterday, text: "Collection failed" }, series: [{ name: "Visitors", type: "line", data: [0] }] }, today);
    const calendar: any = presentChartOption(option, mode);
    expect(calendar.xAxis.data).toEqual(["2026-11-03", yesterday]);
    const warning = marker(calendar);
    expect(warning.markPoint.tooltip.formatter()).toBe("Collection failed");
    expect(warning.markPoint.tooltip.confine).toBe(true);
    expect(warning.markPoint.tooltip.triggerOn).toBe("click");
    expect(calendar.grid.top).toBe(40);
    const timeline: any = presentChartOption(option, { ...mode, axis: "timeline" });
    expect(marker(timeline).markPoint.data[0].xAxis).toBe("2");
    expect(timeline.series[0].data).toEqual([0,null]);
  });
  it("handles partial aggregates, finite historical windows and unknown data without fake failures", () => {
    const base = { collectionDates: [yesterday], partialCollectionDates: [yesterday], xAxis: { data: [yesterday] }, series: [{ name: "Stars", type: "line", data: [2] }] };
    expect(marker(withCollectionWarning(base, today)).markPoint.tooltip.formatter()).toContain("Partial data");
    expect(marker(withCollectionWarning({ ...base, partialCollectionDates: [], collectionWarning: { date: "2026-10-01", text: "old" } }, today))).toBeUndefined();
  });
  it("enforces all observed symbols and confined wrapping tooltips for every actual chart family", () => {
    const history: SnapshotHistory = { repositoryId: 13, kind: "REFERRERS", days: 14, from: yesterday, to: yesterday, dates: [yesterday], rows: [] };
    const options = [rank([{ date: yesterday, position: 1 }], []), trafficChartOption([{ date: yesterday, views: 0, uniqueVisitors: 0, clones: 0, uniqueCloners: 0 }], []), dashboardTrafficOption([{ date: yesterday, views: 0, visitors: 0, clones: 0 }]), statsChartOption([{ date: yesterday, stars: 0, forks: 0, watchers: 0, contributors: 0 }]), storedCountOption({ dates: [yesterday], series: [{ name: "Stars", data: [0] }] }), snapshotChartOption({ history, metric: "VISITORS", selected: [] })];
    for (const source of options) for (const mobile of [false,true]) {
      const result: any = presentChartOption(source, { ...mode, mobile });
      for (const line of result.series.filter((item: any) => item.type === "line" && item.name !== "Events")) expect(line).toMatchObject({ showSymbol: true, showAllSymbol: true });
      expect(result.tooltip.confine).toBe(true);
      expect(result.tooltip.extraCssText).toContain("white-space: normal");
    }
  });
  it("places a known warning on empty numeric and preframed axes without inventing values", () => {
    for (const yAxis of [undefined, [], { inverse: false }, { inverse: true }]) {
      const source = { xAxis: { data: [yesterday] }, yAxis, collectionWarning: { date: yesterday, text: "No snapshot" } };
      const option = withCollectionWarning(source, today);
      expect(marker(option).markPoint.data[0].yAxis).toBe(yAxis && !Array.isArray(yAxis) && yAxis.inverse ? 1 : 0);
    }
    const framed = withCollectionWarning({ xAxis: { data: ["1"] }, eventAxisDates: [yesterday], collectionWarning: { date: yesterday, text: "No snapshot" }, series: [{ name: "empty", type: "line" }] }, today);
    expect(marker(framed).markPoint.data[0].xAxis).toBe("1");
    expect(framed.eventAxisDates).toEqual([yesterday]);
    expect(framed.series?.[0].data).toBeUndefined();
  });

});
