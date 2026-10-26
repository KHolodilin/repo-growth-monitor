import { describe, expect, it } from "vitest";
import type { GrowthEvent } from "./api";
import { presentChartOption, timelineColumns } from "./chartPresentation";

const event: GrowthEvent = {
  id: 1,
  repositoryId: 13,
  eventAt: "2026-09-23T12:00:00Z",
  category: "PROMOTION",
  type: "LINKEDIN_POST",
  title: "Launch",
  source: "MANUAL",
};

describe("chartPresentation", () => {
  it("aligns each series to its own first stored point", () => {
    const framed = timelineColumns(
      ["2026-09-21", "2026-09-22", "2026-09-23"],
      [
        [null, 2, 4],
        [1, null, 3],
      ],
    );
    expect(framed.labels).toEqual(["1", "2", "3"]);
    expect(framed.columns).toEqual([
      [2, 4, null],
      [1, null, 3],
    ]);
    expect(framed.dateToDay.get("2026-09-23")).toBe("3");
    expect(timelineColumns(["2026-09-21"], [[null]]).labels).toEqual([]);
  });

  it("adds markers and rewrites a calendar option onto timeline days", () => {
    const option = presentChartOption(
      {
        tooltip: {
          formatter: (params: { axisValue: string; data: number | null }[]) => `On ${params[0].axisValue}: ${params[0].data}`,
        },
        grid: { top: 40 },
        xAxis: { data: ["2026-09-22", "2026-09-23"], axisLabel: { alignMinLabel: "left" } },
        series: [{ name: "Views", type: "line", data: [null, 4] }],
      },
      { axis: "timeline", events: [event], period: "30d" },
    );
    expect(option.xAxis.data).toEqual(["1"]);
    expect(option.xAxis.axisLabel.formatter("1")).toBe("Day 1");
    expect(option.xAxis.axisLabel.alignMinLabel).toBe("left");
    expect(option.series[0].data).toEqual([4]);
    const events = option.series[1];
    expect(events.name).toBe("Events");
    expect(events.markLine.data[0].xAxis).toBe("1");
    expect(events.markLine.data[0].events[0].title).toBe("Launch");
    expect(events.markPoint).toBeUndefined();
    expect(option.grid.top).toBe(64);
    expect(option.tooltip.formatter([{ axisValue: "1", data: 4 }])).toBe("On Day 1: 4");
    const single = presentChartOption(
      {
        tooltip: { formatter: (params: { name?: string }) => params },
        xAxis: { data: ["2026-09-23"] },
        series: [{ name: "Views", data: [4], markPoint: { data: [{ xAxis: "2026-09-01" }] } }],
      },
      { axis: "timeline", events: [] },
    );
    expect(single.tooltip.formatter({ name: "1" })).toEqual({ name: "2026-09-23" });
    expect(single.series[0].markPoint.data).toEqual([]);
    expect(presentChartOption(option, { axis: "timeline", events: [event] }).xAxis.data).toEqual(["1"]);
    expect(presentChartOption(null, { axis: "calendar", events: [] })).toBeNull();
    expect(presentChartOption({ series: [] }, { axis: "timeline", events: [event] })).toEqual({ series: [] });
    expect(presentChartOption({ xAxis: [], series: [{ data: [1] }] }, { axis: "timeline", events: [event] })).toEqual({
      xAxis: [],
      series: [{ data: [1] }],
    });
    const odd = presentChartOption(
      {
        tooltip: { formatter: (params: { axisValue?: string }[]) => params[0]?.axisValue },
        xAxis: { data: ["2026-09-22", "2026-09-23"] },
        series: [{ name: "Views" }, { name: "Visitors", data: [1, null], markLine: {} }],
      },
      { axis: "timeline", events: [event] },
    );
    expect(odd.xAxis.data).toEqual(["1", "2"]);
    expect(odd.tooltip.formatter([])).toBeUndefined();
    expect(odd.tooltip.formatter([{ axisValue: "9" }, null])).toBe("9");
    expect(odd.tooltip.formatter([{ axisValue: "1", name: "keep" }, null])).toBe("Day 1");
    expect(odd.tooltip.formatter([{ axisValue: "1", name: "1" }])).toBe("Day 1");
    const numeric = presentChartOption(
      {
        tooltip: { formatter: () => 4 },
        xAxis: { data: ["2026-09-23"] },
        series: [{
          name: "Views",
          data: [1],
          markPoint: { data: [{ xAxis: "2026-01-01" }, { xAxis: "2026-09-23" }] },
        }],
      },
      { axis: "timeline", events: [] },
    );
    expect(numeric.tooltip.formatter({ axisValue: "1", name: "1" })).toBe(4);
    expect(numeric.series[0].markPoint.data).toEqual([{ xAxis: "1" }]);
    const kept = presentChartOption(
      {
        grid: { top: "10%" },
        xAxis: { data: ["2026-09-23"] },
        series: [{ name: "Views", data: [1] }],
      },
      { axis: "calendar", events: [{ ...event, eventAt: "2026-01-01T00:00:00Z" }] },
    );
    expect(kept.series).toHaveLength(1);
    expect(kept.grid.top).toBe("10%");
    const labeled = presentChartOption(
      {
        grid: { top: "10%" },
        xAxis: { data: ["2026-09-23"] },
        series: [{ name: "Views", data: [1] }],
      },
      { axis: "calendar", events: [event] },
    );
    expect(labeled.series).toHaveLength(2);
    expect(labeled.grid.top).toBe("10%");
    expect(presentChartOption(
      { xAxis: { data: [] }, series: [] },
      { axis: "timeline", events: [event] },
    ).xAxis.data).toEqual([]);
    const onlyEvents = presentChartOption(
      { xAxis: { data: ["2026-09-23"] } },
      { axis: "calendar", events: [event] },
    );
    expect(onlyEvents.series.map((item) => item.name)).toEqual(["Events"]);
    expect(presentChartOption(
      { xAxis: { data: ["2026-09-23"] } },
      { axis: "timeline", events: [] },
    ).xAxis.data).toEqual(["2026-09-23"]);
    const plain = presentChartOption(
      { tooltip: { formatter: "plain" }, xAxis: { data: ["2026-09-23"] }, series: [{ name: "Views", data: [1] }] },
      { axis: "timeline", events: [] },
    );
    expect(plain.xAxis.data).toEqual(["1"]);
    expect(plain.tooltip.formatter).toBe("plain");
    expect(presentChartOption(
      { xAxis: { data: ["2026-09-23"] }, series: [{ name: "Views", data: [1] }] },
      { axis: "timeline", events: [] },
    ).tooltip).toBeUndefined();
  });

  it("leaves an existing events series and a chart with no stored points", () => {
    const option = {
      xAxis: { data: ["2026-09-23"] },
      series: [
        { name: "Views", data: [null] },
        { name: "Events", data: [null], markLine: { data: [{ xAxis: "2026-09-23" }] } },
      ],
    };
    expect(presentChartOption(option, { axis: "calendar", events: [event] }).series).toHaveLength(2);
    expect(presentChartOption(option, { axis: "timeline", events: [] })).toBe(option);
  });
});
