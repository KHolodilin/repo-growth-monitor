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
