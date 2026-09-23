import { describe, expect, it } from "vitest";
import { snapshotHistory } from "../test/fixtures";
import { snapshotChartOption } from "./snapshotChart";

describe("snapshotChart", () => {
  it("builds series including Other and tooltip gaps", () => {
    const option = snapshotChartOption({
      history: {
        ...snapshotHistory,
        dates: ["2026-09-20", "2026-09-23"],
        rows: [
          ...snapshotHistory.rows,
          {
            key: "reddit.com",
            cells: [
              {
                date: "2026-09-20",
                visitors: 1,
                views: 2,
                visitorsDelta: 1,
                viewsDelta: 1,
                firstSeen: true,
              },
              {
                date: "2026-09-23",
                visitors: 1,
                views: 2,
                visitorsDelta: 0,
                viewsDelta: 0,
                firstSeen: false,
              },
            ],
          },
        ],
      },
      metric: "VISITORS",
      selected: ["github.com"],
    });
    const formatter = option.tooltip.formatter as (
      params: { axisValue: string; seriesName: string; data: number | null }[],
    ) => string;
    expect(formatter([])).toBe("");
    expect(formatter([{ axisValue: "2026-09-23", seriesName: "github.com", data: 1 }])).toContain("Since previous");
    expect(formatter([{ axisValue: "2026-09-20", seriesName: "github.com", data: null }])).toContain("—");
    expect(option.xAxis.axisLabel.formatter("2026-09-23")).toMatch(/Sep/);
    expect(option.series.map((item) => item.name)).toEqual(["github.com", "Other"]);
  });
});
