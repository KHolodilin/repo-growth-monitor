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
    expect(option.series).toHaveLength(3);
    expect(dashboardTrafficOption([{ date: "2026-10-08", views: 1, visitors: 1, clones: 1 }]).xAxis.boundaryGap).toBe(true);
  });

  it("names the repository that contributed the largest share of the day", () => {
    const option = dashboardTrafficOption([
      {
        date: "2026-10-08",
        views: 40,
        visitors: 12,
        clones: 0,
        viewsLeader: { fullName: "owner/outbox", value: 35 },
        visitorsLeader: { fullName: "owner/other", value: 8 },
        clonesLeader: { fullName: "owner/outbox", value: 0 },
      },
    ]);
    const formatter = option.tooltip.formatter;
    const text = formatter([
      { axisValue: "2026-10-08", seriesName: "Views", data: 40 },
      { axisValue: "2026-10-08", seriesName: "Visitors", data: 12 },
      { axisValue: "2026-10-08", seriesName: "Clones", data: 0 },
      { axisValue: "2026-10-08", seriesName: "Other", data: 1 },
    ]);
    expect(text).toContain("+40 views, 35 of them from owner/outbox");
    expect(text).toContain("+12 visitors, 8 of them from owner/other");
    expect(text).not.toContain("+0 clones");
    expect(text).not.toContain("+1 other");
    expect(formatter([{ axisValue: "2026-10-09", seriesName: "Views", data: 40 }])).not.toContain("of them from");
    expect(formatter([{ axisValue: "2026-10-08", seriesName: "Views", data: undefined as unknown as null }])).toContain("—");

    const zeroShare = dashboardTrafficOption([
      { date: "2026-10-08", views: 40, visitors: 1, clones: 1, viewsLeader: { fullName: "owner/outbox", value: 0 } },
    ]);
    expect(zeroShare.tooltip.formatter([{ axisValue: "2026-10-08", seriesName: "Views", data: 40 }])).not.toContain("of them from");
  });
});
