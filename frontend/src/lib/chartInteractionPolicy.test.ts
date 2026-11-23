import { describe, expect, it, vi } from "vitest";
import {
  presentChartOption,
  withCollectionWarning,
  type ChartOption,
  type Series,
} from "./chartPresentation";
import { rankHistoryOption } from "./rankChart";
import { trafficChartOption } from "./trafficChart";
import { dashboardTrafficOption } from "./dashboardChart";
import { statsChartOption } from "./statsChart";
import { storedCountOption } from "./searchSeriesChart";
import { snapshotChartOption } from "./snapshotChart";
import type { SnapshotHistory } from "./api";

const today = "2026-11-05",
  yesterday = "2026-11-04";
const mode = { axis: "calendar" as const, events: [], mobile: true };

const rank = (
  points: { date: string; position: number | null }[] = [{ date: "2026-11-03", position: 1 }],
  missedDates = [yesterday],
  expected = true,
) =>
  rankHistoryOption({
    dates: ["2026-11-03", yesterday],
    series: [{ name: "ours", points, missedDates, expected, limit: 50 }],
  });

function marker(option: ChartOption): Series | undefined {
  return option.series?.find((item) => item.name === "Collection warning");
}

function asChartOption(value: object): ChartOption {
  return value;
}

describe("shared observation and interaction policy", () => {
  it("puts a T-1 ! on portfolio Traffic/Stats when yesterday has no snapshot", () => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(new Date(`${today}T12:00:00Z`));
    try {
      for (const source of [
        dashboardTrafficOption([
          { date: "2026-11-03", views: 10, visitors: 2, clones: 1, uniqueCloners: 1 },
        ]),
        statsChartOption([
          { date: "2026-11-03", stars: 10, forks: 2, watchers: 1, contributors: 3 },
        ]),
      ]) {
        const option = asChartOption(
          presentChartOption(source, {
            axis: "calendar",
            events: [],
            period: "7d",
          }),
        );
        const axis = option.xAxis;
        expect(!Array.isArray(axis) && axis?.data).toContain(yesterday);
        expect(marker(option)?.markPoint?.label?.formatter).toBe("!");
        const tooltip = marker(option)?.markPoint?.tooltip?.formatter;
        expect(typeof tooltip === "function" ? tooltip() : tooltip).toContain("No stored snapshot");
        expect(
          marker(
            asChartOption(
              presentChartOption(
                { ...source, collectionExpected: false },
                { axis: "calendar", events: [], period: "7d" },
              ),
            ),
          ),
        ).toBeUndefined();
      }
    } finally {
      vi.useRealTimers();
    }
  });
  it("removes missed dots but retains observed not-found ranks and connects real observations", () => {
    const option = rank([
      { date: "2026-11-03", position: 1 },
      { date: "2026-11-05", position: null },
    ]);
    expect(option.series).toHaveLength(2);
    expect(rank([{ date: yesterday, position: null }], [yesterday]).series[1].data).toEqual([
      null,
      null,
    ]);
    expect(rank([{ date: yesterday, position: null }], []).series[1].data).toEqual([null, 51]);
    expect(option.series[0]).toMatchObject({ connectNulls: true, data: [1, null] });
    expect(
      option.series.every(
        (item) => item.type !== "scatter" || ("symbol" in item && item.symbol === "diamond"),
      ),
    ).toBe(true);
    const warning = marker(withCollectionWarning(option, today));
    expect(warning?.markPoint?.data?.[0]).toEqual({ xAxis: yesterday, yAxis: 0, y: 38 });
    expect(warning?.markPoint?.label?.formatter).toBe("!");
    const twice = withCollectionWarning(withCollectionWarning(option, today), today);
    expect(twice.series?.filter((item) => item.name === "Collection warning")).toHaveLength(1);
  });
  it("does not invent a warning before tracking, for disabled collection or observed null rank/zero", () => {
    for (const option of [
      rank([], [], true),
      rank(undefined, undefined, false),
      rank([{ date: yesterday, position: null }], []),
      {
        xAxis: { data: [yesterday] },
        collectionDates: [yesterday],
        series: [{ name: "Stars", type: "line", data: [0] }],
      },
    ]) {
      expect(marker(withCollectionWarning(option, today))).toBeUndefined();
    }
    expect(withCollectionWarning({ xAxis: [], series: [] }, today)).toEqual({
      xAxis: [],
      series: [],
    });
  });
  it("keeps an explicit failure reason and a metric-independent warning through timeline framing", () => {
    const option = withCollectionWarning(
      {
        xAxis: { data: ["2026-11-03"] },
        collectionDates: ["2026-11-03"],
        collectionWarning: { date: yesterday, text: "Collection failed" },
        series: [{ name: "Visitors", type: "line", data: [0] }],
      },
      today,
    );
    const calendar = asChartOption(presentChartOption(option, mode));
    const calendarAxis = calendar.xAxis;
    expect(!Array.isArray(calendarAxis) && calendarAxis?.data).toEqual(["2026-11-03", yesterday]);
    const warning = marker(calendar);
    const calendarFormatter = warning?.markPoint?.tooltip?.formatter;
    expect(typeof calendarFormatter === "function" ? calendarFormatter() : calendarFormatter).toBe(
      "Collection failed",
    );
    expect(warning?.markPoint?.tooltip?.confine).toBe(true);
    expect(warning?.markPoint?.tooltip?.triggerOn).toBe("click");
    expect(calendar.grid?.top).toBe(40);
    const timeline = asChartOption(presentChartOption(option, { ...mode, axis: "timeline" }));
    expect(marker(timeline)?.markPoint?.data?.[0]?.xAxis).toBe("2");
    expect(timeline.series?.[0]?.data).toEqual([0, null]);
  });
  it("handles partial aggregates, finite historical windows and unknown data without fake failures", () => {
    const base = {
      collectionDates: [yesterday],
      partialCollectionDates: [yesterday],
      xAxis: { data: [yesterday] },
      series: [{ name: "Stars", type: "line", data: [2] }],
    };
    const partialFormatter = marker(withCollectionWarning(base, today))?.markPoint?.tooltip
      ?.formatter;
    expect(
      typeof partialFormatter === "function" ? partialFormatter() : partialFormatter,
    ).toContain("Partial data");
    expect(
      marker(
        withCollectionWarning(
          {
            ...base,
            partialCollectionDates: [],
            collectionWarning: { date: "2026-10-01", text: "old" },
          },
          today,
        ),
      ),
    ).toBeUndefined();
  });
  it("enforces all observed symbols and confined wrapping tooltips for every actual chart family", () => {
    const history: SnapshotHistory = {
      repositoryId: 13,
      kind: "REFERRERS",
      days: 14,
      from: yesterday,
      to: yesterday,
      dates: [yesterday],
      rows: [],
    };
    const options = [
      rank([{ date: yesterday, position: 1 }], []),
      trafficChartOption(
        [{ date: yesterday, views: 0, uniqueVisitors: 0, clones: 0, uniqueCloners: 0 }],
        [],
      ),
      dashboardTrafficOption([{ date: yesterday, views: 0, visitors: 0, clones: 0 }]),
      statsChartOption([{ date: yesterday, stars: 0, forks: 0, watchers: 0, contributors: 0 }]),
      storedCountOption({ dates: [yesterday], series: [{ name: "Stars", data: [0] }] }),
      snapshotChartOption({ history, metric: "VISITORS", selected: [] }),
    ];
    for (const source of options)
      for (const mobile of [false, true]) {
        const result = asChartOption(presentChartOption(source, { ...mode, mobile }));
        for (const line of (result.series ?? []).filter(
          (item) => item.type === "line" && item.name !== "Events",
        ))
          expect(line).toMatchObject({ showSymbol: true, showAllSymbol: true });
        const tooltip = result.tooltip as
          { confine?: boolean; extraCssText?: string; position?: unknown } | undefined;
        expect(tooltip?.confine).toBe(true);
        expect(tooltip?.extraCssText).toContain("white-space: normal");
        expect(tooltip?.extraCssText).toContain("max-height: min(220px, calc(100vh - 96px))");
        expect(typeof tooltip?.position).toBe("function");
        expect(tooltip?.extraCssText).toContain("overflow-y: auto");
      }
  });
  it("places a known warning on empty numeric and preframed axes without inventing values", () => {
    for (const yAxis of [undefined, [], { inverse: false }, { inverse: true }]) {
      const source = {
        xAxis: { data: [yesterday] },
        yAxis,
        collectionWarning: { date: yesterday, text: "No snapshot" },
      };
      const option = withCollectionWarning(source, today);
      expect(marker(option)?.markPoint?.data?.[0]).toMatchObject({ yAxis: 0, y: 54 });
    }
    const framed = withCollectionWarning(
      {
        xAxis: { data: ["1"] },
        eventAxisDates: [yesterday],
        collectionWarning: { date: yesterday, text: "No snapshot" },
        series: [{ name: "empty", type: "line" }],
      },
      today,
    );
    expect(marker(framed)?.markPoint?.data?.[0]?.xAxis).toBe("1");
    expect(framed.eventAxisDates).toEqual([yesterday]);
    expect(framed.series?.[0].data).toBeUndefined();
  });
});
