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
    expect(option.tooltip.trigger).toBe("item");
    expect(option.tooltip.triggerOn).toBe("click");
    expect(option.xAxis.boundaryGap).toBe(false);
    expect(option.series).toHaveLength(3);
    expect(formatter({})).toBe("");
    expect(dashboardTrafficOption([{ date: "2026-10-08", views: 1, visitors: 1, clones: 1 }]).xAxis.boundaryGap).toBe(true);
  });

  it("names the repository that contributed the largest share of the day", () => {
    const option = dashboardTrafficOption([
      {
        date: "2026-10-08",
        views: 40,
        visitors: 12,
        clones: 6,
        viewsLeaders: [
          { fullName: "owner/outbox", value: 35 },
          { fullName: "owner/zeta", value: 0 },
          { fullName: "owner/tiny", value: 10 },
          { fullName: "owner/beta", value: 4 },
          { fullName: "owner/extra", value: 1 },
        ],
        visitorsLeaders: [{ fullName: "owner/other", value: 8 }],
        clonesLeaders: [{ fullName: "owner/outbox", value: 5 }],
      },
    ]);
    const formatter = option.tooltip.formatter;
    const text = formatter([
      { axisValue: "2026-10-08", seriesName: "Views", data: 40 },
      { axisValue: "2026-10-08", seriesName: "Visitors", data: 12 },
      { axisValue: "2026-10-08", seriesName: "Clones", data: 0 },
      { axisValue: "2026-10-08", seriesName: "Other", data: 1 },
    ]);
    expect(text).toContain("35 from owner/outbox");
    expect(text).toContain("10 from owner/tiny");
    expect(text).toContain("4 from owner/beta");
    expect(text).toContain("8 from owner/other");
    expect(text).not.toContain("owner/extra");
    expect(text).not.toContain("owner/zeta");
    expect(text).not.toContain("from owner/outbox</div><div>5 from");
    const views = formatter({ name: "2026-10-08", seriesName: "Views", data: 40 });
    expect(views).toContain("35 from owner/outbox");
    expect(views).not.toContain("Visitors");
    expect(views).not.toContain("Clones");
    const clones = formatter({ name: "2026-10-08", seriesName: "Clones", data: 6 });
    expect(clones).toContain("5 from owner/outbox");
    expect(clones).not.toContain("Views");
    expect(clones).not.toContain("Visitors");
    expect(formatter([{ axisValue: "2026-10-09", seriesName: "Views", data: 40 }])).not.toContain(" from ");
    expect(formatter({ name: "2026-10-09", seriesName: "Views", data: 40 })).not.toContain(" from ");
    expect(formatter({ seriesName: "Views", data: 1 })).toBe("");
    expect(formatter([{ axisValue: "2026-10-08", seriesName: "Views", data: undefined as unknown as null }])).toContain("—");

    const zeroShare = dashboardTrafficOption([
      { date: "2026-10-08", views: 40, visitors: 1, clones: 1, viewsLeaders: [{ fullName: "owner/outbox", value: 0 }] },
    ]);
    expect(zeroShare.tooltip.formatter([{ axisValue: "2026-10-08", seriesName: "Views", data: 40 }])).not.toContain(" from ");
    const none = dashboardTrafficOption([
      { date: "2026-10-08", views: 40, visitors: 1, clones: 1, viewsLeaders: [] },
    ]);
    expect(none.tooltip.formatter([{ axisValue: "2026-10-08", seriesName: "Views", data: 40 }])).not.toContain(" from ");
  });
});
