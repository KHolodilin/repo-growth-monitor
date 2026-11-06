import { describe, expect, it, vi } from "vitest";
import * as echarts from "echarts";
import { presentChartOption } from "./chartPresentation";
import { trafficChartOption } from "./trafficChart";
import { dashboardTrafficOption } from "./dashboardChart";
import { statsChartOption } from "./statsChart";
import { rankHistoryOption } from "./rankChart";
import { storedCountOption } from "./searchSeriesChart";
import { snapshotChartOption } from "./snapshotChart";

const date = "2026-11-05", previous = "2026-11-04";
function render(option: object, mobile = false) {
  const width = mobile ? 320 : 800, height = mobile ? 220 : 360;
  const chart = echarts.init(null, null, { renderer: "svg", ssr: true, width, height });
  try {
    chart.setOption(option as never);
    const svg = chart.renderToSVGString();
    const label = svg.match(/<text[^>]*transform="translate\(([-\d.]+) ([-\d.]+)\)"[^>]*>!<\/text>/);
    expect(label, "the warning must actually render, not merely exist in the option").not.toBeNull();
    expect(Number(label![1])).toBeGreaterThanOrEqual(0);
    expect(Number(label![1])).toBeLessThanOrEqual(width);
    expect(Number(label![2])).toBeGreaterThanOrEqual(11);
    expect(Number(label![2])).toBeLessThan(height - 20);
    return svg;
  } finally { chart.dispose(); }
}

describe("missing-collection warning rendering", () => {
  it("survives toggling between a hidden high metric, low values and zero without changing the axis scale", () => {
    vi.useFakeTimers({ toFake: ["Date"] }); vi.setSystemTime(new Date("2026-11-06T12:00:00Z"));
    try {
      for (const mobile of [false, true]) for (const axis of ["calendar", "timeline"] as const) for (const value of [14, 0]) {
        const source = trafficChartOption([{ date: previous, views: 102, uniqueVisitors: value, clones: 35, uniqueCloners: 16 }], [], "30d", { reason: "GITHUB_DATA_MISSING", date });
        for (const active of ["Views", "Visitors", "Views", "Visitors"]) {
          const option: any = presentChartOption({ ...source, legend: { show: false, data: ["Views", "Visitors", "Clones", "Unique Cloners"], selected: { Views: active === "Views", Visitors: active === "Visitors", Clones: false, "Unique Cloners": false } } }, { axis, events: [], period: "30d", mobile });
          render(option, mobile);
          const warning = option.series.find((item: any) => item.name === "Collection warning");
          expect(warning.markPoint.data[0].xAxis).toBe(axis === "calendar" ? date : "2");
          expect(warning.markPoint.tooltip.formatter()).toContain("Nov 5");
          // The marker is presentation metadata, not an invented traffic value.
          expect(warning.data.every((point: unknown) => point == null)).toBe(true);
          expect(warning.markPoint.data[0].yAxis).toBe(0);
        }
      }
    } finally { vi.useRealTimers(); }
  });

  it("renders across every chart family, including inverted rank axes, empty data and resized plots", () => {
    vi.useFakeTimers({ toFake: ["Date"] }); vi.setSystemTime(new Date("2026-11-06T12:00:00Z"));
    try {
      const sources = [
        dashboardTrafficOption([{ date: previous, views: 102, visitors: 14, clones: 35 }]),
        statsChartOption([{ date: previous, stars: 102, forks: 14, watchers: 0, contributors: 3 }]),
        rankHistoryOption({ dates: [previous, date], series: [{ name: "ours", points: [{ date: previous, position: 1 }], missedDates: [date], expected: true, limit: 50 }] }),
        storedCountOption({ dates: [previous, date], series: [{ name: "Stars", data: [102, null] }] }),
        snapshotChartOption({ history: { repositoryId: 13, kind: "PATHS", days: 2, from: previous, to: date, dates: [previous, date], rows: [] }, metric: "VIEWS", selected: [] }),
        { xAxis: { type: "category", data: [date] }, yAxis: { type: "value" }, series: [], grid: { top: "20%" } },
      ];
      for (const source of sources) for (const mobile of [false, true]) for (const axis of ["calendar", "timeline"] as const) {
        const option: any = presentChartOption({ ...source, collectionWarning: { date, text: "No stored snapshot" } }, { axis, events: [], period: "30d", mobile });
        render(option, mobile);
        const warning = option.series.find((item: any) => item.name === "Collection warning");
        expect(warning.markPoint.tooltip.formatter()).toBe("No stored snapshot");
        expect(warning.markPoint.data[0].y).toBe((typeof option.grid?.top === "number" ? option.grid.top : 40) + 14);
      }
    } finally { vi.useRealTimers(); }
  });
});