import { describe, expect, it } from "vitest";
import { dashboardTrafficOption } from "./dashboardChart";

describe("dashboardTrafficOption", () => {
  it("formats tooltip rows and treats a short series as a point chart", () => {
    const option = dashboardTrafficOption([
      { date: "2026-10-08", views: null, visitors: 3, clones: 1 },
      { date: "2026-10-09", views: 2, visitors: null, clones: null },
    ]);
    const formatter = option.tooltip.formatter;
    expect(formatter("nope")).toBe("");
    expect(formatter([])).toBe("");
    expect(formatter([
      { axisValue: "2026-10-08", seriesName: "Views", data: null },
      { axisValue: "2026-10-08", seriesName: "Visitors", data: 3 },
    ])).toContain("Visitors");
    expect(option.xAxis.boundaryGap).toBe(false);
    expect(dashboardTrafficOption([{ date: "2026-10-08", views: 1, visitors: 1, clones: 1 }]).xAxis.boundaryGap).toBe(true);
  });
});
