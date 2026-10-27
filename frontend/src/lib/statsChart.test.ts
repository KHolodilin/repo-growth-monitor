import { describe, expect, it } from "vitest";
import { statsChartOption } from "./statsChart";

describe("statsChart", () => {
  it("uses click tooltips with leaders and leaves empty days without an axis pointer", () => {
    const option = statsChartOption([
      {
        date: "2026-09-23",
        stars: 21,
        forks: 16,
        watchers: 0,
        contributors: 7,
        starsLeaders: [
          { fullName: "owner/outbox", value: 18 },
          { fullName: "owner/zeta", value: 0 },
          { fullName: "owner/tiny", value: 2 },
          { fullName: "owner/beta", value: 1 },
          { fullName: "owner/extra", value: 1 },
        ],
        forksLeaders: [{ fullName: "owner/outbox", value: 10 }],
        contributorsLeaders: [{ fullName: "owner/other", value: 7 }],
      },
    ]);
    const formatter = option.tooltip.formatter;
    expect(option.tooltip.trigger).toBe("item");
    expect(option.tooltip.triggerOn).toBe("click");
    expect(option.axisPointer.show).toBe(false);
    expect(option.animation).toBe(false);
    expect(formatter("nope")).toBe("");
    expect(formatter([])).toBe("");
    expect(formatter({})).toBe("");
    expect(formatter({ seriesName: "Stars", data: 21 })).toBe("");
    const stars = formatter({ name: "2026-09-23", seriesName: "Stars", data: 21 });
    expect(stars).toContain("Stars - 21");
    expect(stars).toContain("owner/outbox - 18");
    expect(stars).toContain("owner/tiny - 2");
    expect(stars).not.toContain("owner/extra");
    expect(stars).not.toContain("owner/zeta");
    expect(stars).not.toContain("Forks");
    const forks = formatter([
      { axisValue: "2026-09-23", seriesName: "Forks", data: 16 },
      { axisValue: "2026-09-23", seriesName: "Watchers", data: null },
    ]);
    expect(forks).toContain("Forks - 16");
    expect(forks).toContain("owner/outbox - 10");
    expect(forks).toContain("Watchers - —");
    expect(formatter([{ axisValue: "2026-09-23", seriesName: "Stars", data: { value: 21 } }])).toContain("Stars - 21");
    expect(formatter([{ axisValue: "2026-09-23", seriesName: "Stars", data: {} }])).toContain("Stars - —");
    expect(formatter([{ axisValue: "2026-09-23", seriesName: "Stars", data: undefined as unknown as null }])).toContain(
      "Stars - —",
    );
    expect(option.xAxis.boundaryGap).toBe(true);
    expect(option.xAxis.axisLabel.formatter("2026-09-23")).toMatch(/Sep/);
    expect(option.legend).toMatchObject({ top: 0 });
  });

  it("marks partial stats days and skips share rows when leaders are empty", () => {
    const partial = statsChartOption([
      { date: "2026-09-23", stars: 21, forks: 16, watchers: 0, contributors: 7, missingRepositories: 2 },
      { date: "2026-09-24", stars: null, forks: null, watchers: null, contributors: null, missingRepositories: 2 },
    ]);
    const formatter = partial.tooltip.formatter;
    expect(formatter([{ axisValue: "2026-09-23", seriesName: "Stars", data: 21 }])).toContain("missing stats data");
    expect(formatter([{ axisValue: "2026-09-24", seriesName: "Stars", data: null }])).not.toContain("missing stats data");
    expect(partial.xAxis.boundaryGap).toBe(false);
    const none = statsChartOption([
      { date: "2026-09-23", stars: 21, forks: 1, watchers: 1, contributors: 1, starsLeaders: [] },
    ]);
    expect(none.tooltip.formatter({ name: "2026-09-23", seriesName: "Stars", data: 21 })).not.toContain("owner/");
    const zero = statsChartOption([
      {
        date: "2026-09-23",
        stars: 21,
        forks: 1,
        watchers: 1,
        contributors: 1,
        starsLeaders: [{ fullName: "owner/outbox", value: 0 }],
      },
    ]);
    expect(zero.tooltip.formatter({ name: "2026-09-23", seriesName: "Stars", data: 21 })).not.toContain("owner/outbox");
    const named = statsChartOption([
      {
        date: "2026-09-23",
        stars: 21,
        forks: 16,
        watchers: 4,
        contributors: 7,
        watchersLeaders: [{ fullName: "owner/watch", value: 4 }],
        contributorsLeaders: [{ fullName: "owner/contrib", value: 7 }],
      },
    ]);
    const namedFormatter = named.tooltip.formatter;
    expect(namedFormatter({ name: "2026-09-23", seriesName: "Watchers", data: 4 })).toContain("owner/watch - 4");
    expect(namedFormatter({ name: "2026-09-23", seriesName: "Contributors", data: 7 })).toContain("owner/contrib - 7");
    expect(namedFormatter({ name: "2026-09-23", seriesName: "Other", data: 1 })).toContain("Other - 1");
    expect(namedFormatter([{ axisValue: "2026-09-24", seriesName: "Stars", data: 21 }])).toContain("Stars - 21");
  });
});

