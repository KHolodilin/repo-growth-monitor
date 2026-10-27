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
    expect(option.legend).toMatchObject({ top: 0 });
    expect(option.xAxis.axisLabel.formatter("2026-09-23")).toMatch(/Sep/);
    expect(option.xAxis.boundaryGap).toBe(true);
    expect(option.series.every((item) => item.connectNulls === true)).toBe(true);
    const partial = statsChartOption([
      { date: "2026-09-23", stars: 21, forks: 16, watchers: 0, contributors: 7, missingRepositories: 2 },
      { date: "2026-09-24", stars: null, forks: null, watchers: null, contributors: null, missingRepositories: 2 },
    ]);
    const partialFormatter = partial.tooltip.formatter as (
      params: { axisValue: string; seriesName: string; data?: number | null }[],
    ) => string;
    expect(partialFormatter([{ axisValue: "2026-09-23", seriesName: "Stars", data: 21 }])).toContain(
      "missing stats data",
    );
    expect(partialFormatter([{ axisValue: "2026-09-24", seriesName: "Stars", data: null }])).not.toContain(
      "missing stats data",
    );
  });
});
