import { describe, expect, it } from "vitest";
import {
  CHART_AXIS_COOKIE,
  CHART_EVENT_FILTER_COOKIE,
  readChartAxis,
  readChartEventFilter,
  subscribeChartMode,
  writeChartAxis,
  writeChartEventFilter,
} from "./chartModePrefs";

describe("chartModePrefs", () => {
  it("keeps one event filter and one axis for every chart", () => {
    expect(readChartEventFilter()).toBe("all");
    expect(readChartAxis()).toBe("calendar");
    const seen: string[] = [];
    const stop = subscribeChartMode(() => seen.push(`${readChartEventFilter()}:${readChartAxis()}`));
    writeChartEventFilter("off");
    writeChartAxis("timeline");
    expect(readChartEventFilter()).toBe("off");
    expect(readChartAxis()).toBe("timeline");
    expect(document.cookie).toContain(`${CHART_EVENT_FILTER_COOKIE}=off`);
    expect(document.cookie).toContain(`${CHART_AXIS_COOKIE}=timeline`);
    expect(seen).toEqual(["off:calendar", "off:timeline"]);
    stop();
    writeChartEventFilter("github");
    expect(seen).toEqual(["off:calendar", "off:timeline"]);
  });

  it("ignores a cookie that is not a chart mode", () => {
    document.cookie = `${CHART_EVENT_FILTER_COOKIE}=nope; path=/`;
    document.cookie = `${CHART_AXIS_COOKIE}=week; path=/`;
    expect(readChartEventFilter()).toBe("all");
    expect(readChartAxis()).toBe("calendar");
  });
});
