import { describe, expect, it } from "vitest";
import {
  CHART_MODE_COOKIE,
  readChartAxis,
  readChartEventFilter,
  subscribeChartMode,
  writeChartAxis,
  writeChartEventFilter,
} from "./chartModePrefs";

describe("chartModePrefs", () => {
  it("stores the choice on the chart that changed", () => {
    expect(readChartEventFilter("portfolio:traffic")).toBe("all");
    expect(readChartAxis("portfolio:traffic")).toBe("calendar");
    const seen: string[] = [];
    const stop = subscribeChartMode("portfolio:traffic", () => {
      seen.push(`${readChartEventFilter("portfolio:traffic")}:${readChartAxis("portfolio:traffic")}`);
    });
    writeChartAxis("repository:13:stats", "timeline");
    writeChartEventFilter("portfolio:traffic", "off");
    writeChartAxis("portfolio:traffic", "timeline");
    expect(readChartAxis("repository:13:stats")).toBe("timeline");
    expect(readChartEventFilter("repository:13:stats")).toBe("all");
    expect(readChartEventFilter("portfolio:traffic")).toBe("off");
    expect(readChartAxis("portfolio:traffic")).toBe("timeline");
    expect(document.cookie).toContain(CHART_MODE_COOKIE);
    expect(seen).toEqual(["off:calendar", "off:timeline"]);
    const stopAgain = subscribeChartMode("portfolio:traffic", () => {
      seen.push("again");
    });
    writeChartEventFilter("portfolio:traffic", "github");
    expect(seen).toEqual(["off:calendar", "off:timeline", "github:timeline", "again"]);
    stop();
    stopAgain();
    writeChartEventFilter("portfolio:traffic", "promotion");
    expect(seen).toEqual(["off:calendar", "off:timeline", "github:timeline", "again"]);
  });

  it("ignores a cookie that is not a chart mode", () => {
    document.cookie = `${CHART_MODE_COOKIE}=nope; path=/`;
    expect(readChartAxis("portfolio:traffic")).toBe("calendar");
    document.cookie = `${CHART_MODE_COOKIE}=${encodeURIComponent(JSON.stringify({
      "portfolio:traffic": { eventFilter: "nope", axis: "week" },
      "repository:13:stats": { axis: "timeline" },
      "repository:8:traffic": [],
    }))}; path=/`;
    expect(readChartEventFilter("portfolio:traffic")).toBe("all");
    expect(readChartAxis("portfolio:traffic")).toBe("calendar");
    expect(readChartAxis("repository:13:stats")).toBe("timeline");
    expect(readChartAxis("repository:8:traffic")).toBe("calendar");
    for (const raw of [null, "text", 1, []]) {
      document.cookie = `${CHART_MODE_COOKIE}=${encodeURIComponent(JSON.stringify(raw))}; path=/`;
      expect(readChartEventFilter("portfolio:traffic")).toBe("all");
    }
    document.cookie = `${CHART_MODE_COOKIE}=${encodeURIComponent(JSON.stringify({
      "repository:1:stats": { eventFilter: "promotion" },
      "repository:2:traffic": null,
    }))}; path=/`;
    expect(readChartEventFilter("repository:1:stats")).toBe("promotion");
    expect(readChartAxis("repository:1:stats")).toBe("calendar");
    expect(readChartAxis("repository:2:traffic")).toBe("calendar");
  });
});
