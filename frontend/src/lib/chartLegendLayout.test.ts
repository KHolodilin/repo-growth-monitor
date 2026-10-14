import { describe, expect, it } from "vitest";
import * as echarts from "echarts";
import { dashboardTrafficOption } from "./dashboardChart";
import { rankHistoryOption } from "./rankChart";
import { statsChartOption } from "./statsChart";
import { trafficChartOption } from "./trafficChart";

const CHART_HEIGHT = 360;
/** Plot starts at grid.top (40–48). A legend on the x-axis sits near the bottom of the canvas. */
const TOP_BAND = 40;

function legendTop(option: object) {
  const chart = echarts.init(null, null, { renderer: "svg", ssr: true, width: 800, height: CHART_HEIGHT });
  chart.setOption(option as never);
  const laidOut = chart as unknown as {
    getModel: () => { getComponent: (mainType: string, index: number) => object };
    getViewOfComponentModel: (componentModel: object) => { group: { y: number } };
  };
  const model = laidOut.getModel().getComponent("legend", 0);
  const top = laidOut.getViewOfComponentModel(model).group.y;
  chart.dispose();
  return top;
}

describe("chart legend layout", () => {
  it("keeps every legend in the top band", () => {
    const tops = {
      traffic: legendTop(
        trafficChartOption(
          [{ date: "2026-10-08", views: 5, uniqueVisitors: 3, clones: 1, uniqueCloners: 1 }],
          [],
        ),
      ),
      dashboard: legendTop(dashboardTrafficOption([{ date: "2026-10-08", views: 5, visitors: 3, clones: 1 }])),
      stats: legendTop(
        statsChartOption([{ date: "2026-10-08", stars: 21, forks: 16, watchers: 0, contributors: 7 }]),
      ),
      rank: legendTop(
        rankHistoryOption({
          dates: ["2026-10-08", "2026-10-09"],
          legend: true,
          series: [
            {
              name: "outbox",
              limit: 50,
              points: [
                { date: "2026-10-08", position: 4 },
                { date: "2026-10-09", position: 3 },
              ],
            },
          ],
        }),
      ),
    };

    expect(tops.traffic).toBeLessThan(TOP_BAND);
    expect(tops.dashboard).toBeLessThan(TOP_BAND);
    expect(tops.stats).toBeLessThan(TOP_BAND);
    expect(tops.rank).toBeLessThan(TOP_BAND);
  });
});
