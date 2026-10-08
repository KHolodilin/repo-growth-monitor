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
    expect(overlays.markLine.tooltip.formatter({ data: {} })).toBe("");
    expect(overlays.markLine.tooltip.formatter({ data: { events: [event("2026-09-20", "A")] } })).toContain("A");
    expect(overlays.markPoint.label.formatter({ data: { events: [event("2026-09-20", "A"), event("2026-09-20", "B", 2)] } })).toBe(
      "2",
    );
    expect(overlays.markPoint.label.formatter({ data: {} })).toBe("");
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
    expect(option.xAxis.axisLabel.formatter("2026-09-23")).toMatch(/Sep/);
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
      { offset: [0, 0] },
      { offset: [0, 0] },
    ]);
  });

  it("stacks neighbouring days on different rows", () => {
    const overlays = eventMarkOverlays(days(30), [
      event("2026-01-01", "A", 1),
      event("2026-01-02", "B", 2),
    ]);
    expect(overlays.markLine.data.map((item) => item.label)).toEqual([
      { offset: [0, 0] },
      { offset: [0, -15] },
    ]);
  });

  it("hides a label that has no free row and shows the count on its pin", () => {
    const overlays = eventMarkOverlays(days(60), [
      event("2026-01-01", "A", 1),
      event("2026-01-02", "B", 2),
      event("2026-01-03", "C", 3),
      event("2026-01-04", "D", 4),
      event("2026-01-05", "E", 5),
    ]);
    const hidden = overlays.markLine.data.find((item) => item.xAxis === "2026-01-05");
    const pin = overlays.markPoint.data.find((item) => item.xAxis === "2026-01-05");
    const visiblePin = overlays.markPoint.data.find((item) => item.xAxis === "2026-01-01");
    expect(hidden?.label).toEqual({ show: false });
    expect(overlays.markPoint.label.formatter({ data: pin })).toBe("1");
    expect(overlays.markPoint.label.formatter({ data: visiblePin })).toBe("");
  });
});

function days(count: number) {
  return Array.from({ length: count }, (_, index) => {
    const date = new Date(Date.UTC(2026, 0, 1 + index));
    return date.toISOString().slice(0, 10);
  });
}
