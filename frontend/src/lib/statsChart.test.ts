import { describe, expect, it } from "vitest";
import { statsChartOption } from "./statsChart";

describe("statsChart", () => {
  it("formats empty and populated tooltips", () => {
    const option = statsChartOption([
      { date: "2026-09-23", stars: 21, forks: 16, watchers: 0, contributors: 7 },
    ]);
    const formatter = option.tooltip.formatter as (
      params: { axisValue: string; seriesName: string; data?: number | null }[],
    ) => string;
    expect(formatter([])).toBe("");
    expect(
      formatter([
        { axisValue: "2026-09-23", seriesName: "Stars", data: 21 },
        { axisValue: "2026-09-23", seriesName: "Forks", data: null },
      ]),
    ).toContain("Stars");
    expect(option.xAxis.axisLabel.formatter("2026-09-23")).toMatch(/Sep/);
    expect(option.xAxis.boundaryGap).toBe(true);
  });
});
