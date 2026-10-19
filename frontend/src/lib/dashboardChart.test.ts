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
    expect(option.series.some((series) => series.lineStyle?.type === "dashed")).toBe(false);
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
    expect(text).toContain("Views - 40");
    expect(text).toContain("owner/outbox - 35");
    expect(text).toContain("owner/tiny - 10");
    expect(text).toContain("owner/beta - 4");
    expect(text).toContain("owner/other - 8");
    expect(text).not.toContain("owner/extra");
    expect(text).not.toContain("owner/zeta");
    expect(text).not.toContain("owner/outbox - 5");
    expect(text).not.toContain("from");
    const views = formatter({ name: "2026-10-08", seriesName: "Views", data: 40 });
    expect(views).toContain("Views - 40");
    expect(views).toContain("owner/outbox - 35");
    expect(views).not.toContain("Visitors");
    expect(views).not.toContain("Clones");
    const clones = formatter({ name: "2026-10-08", seriesName: "Clones", data: 6 });
    expect(clones).toContain("Clones - 6");
    expect(clones).toContain("owner/outbox - 5");
    expect(clones).not.toContain("Views");
    expect(clones).not.toContain("Visitors");
    expect(formatter([{ axisValue: "2026-10-09", seriesName: "Views", data: 40 }])).toBe("<div><div>Oct 9</div><div>Views - 40</div></div>");
    expect(formatter({ name: "2026-10-09", seriesName: "Views", data: 40 })).toBe("<div><div>Oct 9</div><div>Views - 40</div></div>");
    expect(formatter({ seriesName: "Views", data: 1 })).toBe("");
    expect(formatter([{ axisValue: "2026-10-08", seriesName: "Views", data: undefined as unknown as null }])).toContain("Views - —");
    expect(formatter([{ axisValue: "2026-10-08", seriesName: "Views", data: { value: 40 } }])).toContain("Views - 40");
    expect(formatter([{ axisValue: "2026-10-08", seriesName: "Views", data: {} }])).toContain("Views - —");

    const zeroShare = dashboardTrafficOption([
      { date: "2026-10-08", views: 40, visitors: 1, clones: 1, viewsLeaders: [{ fullName: "owner/outbox", value: 0 }] },
    ]);
    expect(zeroShare.tooltip.formatter([{ axisValue: "2026-10-08", seriesName: "Views", data: 40 }])).not.toContain("owner/outbox");
    const none = dashboardTrafficOption([
      { date: "2026-10-08", views: 40, visitors: 1, clones: 1, viewsLeaders: [] },
    ]);
    expect(none.tooltip.formatter([{ axisValue: "2026-10-08", seriesName: "Views", data: 40 }])).not.toContain("owner/outbox");
  });

  it("keeps a partial day on the solid line and a stored zero solid", () => {
    const option = dashboardTrafficOption([
      { date: "2026-10-17", views: 10, visitors: 4, clones: 1, missingRepositories: 0 },
      { date: "2026-10-18", views: 7, visitors: 3, clones: 1, missingRepositories: 2 },
    ]);
    expect(option.series.find((series) => series.name === "Views")?.data).toEqual([10, 7]);
    const text = option.tooltip.formatter([{ axisValue: "2026-10-18", seriesName: "Views", data: 7 }]);
    expect(text).toContain("Partial data — 2 repositories missing traffic data for 18 Oct");
    const solid = dashboardTrafficOption([{ date: "2026-10-18", views: 0, visitors: 0, clones: 0, missingRepositories: 0 }]);
    expect(solid.tooltip.formatter([{ name: "2026-10-18", seriesName: "Views", data: 0 }])).not.toContain("Partial data");

    const middle = dashboardTrafficOption([
      { date: "2026-10-16", views: 4, visitors: 1, clones: 1, missingRepositories: 0 },
      { date: "2026-10-17", views: 5, visitors: 1, clones: 1, missingRepositories: 2 },
      { date: "2026-10-18", views: 6, visitors: 1, clones: 1, missingRepositories: 0 },
    ]);
    expect(middle.series.find((series) => series.name === "Views")?.data).toEqual([4, 5, 6]);
    const hole = dashboardTrafficOption([
      { date: "2026-10-16", views: 4, visitors: 1, clones: 1 },
      { date: "2026-10-17", views: null, visitors: null, clones: null, missingRepositories: 3 },
      { date: "2026-10-18", views: 6, visitors: 1, clones: 1 },
    ]);
    expect(hole.series.find((series) => series.name === "Views")?.data).toEqual([4, null, 6]);
  });
});
