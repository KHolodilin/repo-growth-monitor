import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { PersistentECharts } from "../components/PersistentECharts";
import type { GrowthEvent } from "./api";
import { chartCsv, chartExportBase, downloadChartCsv, downloadChartPng } from "./chartExport";
import { dashboardTrafficOption } from "./dashboardChart";
import { rankHistoryOption } from "./rankChart";
import { statsChartOption } from "./statsChart";
import { trafficChartOption } from "./trafficChart";

const event: GrowthEvent = {
  id: 1,
  repositoryId: 13,
  eventAt: "2026-09-21T12:00:00Z",
  category: "PROMOTION",
  type: "LINKEDIN_POST",
  title: "Launch",
  source: "MANUAL",
};

describe("chartExport", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("names a file from the chart id", () => {
    expect(chartExportBase("13:traffic")).toBe("13-traffic");
    expect(chartExportBase(":::")).toBe("chart");
  });

  it("writes one column per line series and leaves missing ranks empty", () => {
    const rank = rankHistoryOption({
      dates: ["2026-09-22", "2026-09-23", "2026-09-24"],
      series: [
        {
          name: "outbox",
          limit: 50,
          missedDates: ["2026-09-22"],
          points: [
            { date: "2026-09-22", position: null },
            { date: "2026-09-23", position: 24 },
            { date: "2026-09-24", position: null },
          ],
        },
      ],
    });
    expect(chartCsv(rank)).toBe("date,outbox\n2026-09-22,\n2026-09-23,24\n2026-09-24,");
    expect(chartCsv(rank)).not.toContain("51");
    expect(chartCsv(rank)).not.toMatch(/Sep/);

    const traffic = trafficChartOption(
      [{ date: "2026-09-23", views: 8, uniqueVisitors: 3, clones: 2, uniqueCloners: 1 }],
      [event],
    );
    expect(chartCsv(traffic)).toBe("date,Views,Visitors,Unique Cloners\n2026-09-21,,,\n2026-09-23,8,3,1");
    expect(chartCsv(traffic)).not.toContain("Events");

    expect(
      chartCsv(
        statsChartOption([{ date: "2026-09-23", stars: 21, forks: 16, watchers: 0, contributors: 7 }]),
      ),
    ).toBe("date,Stars,Forks,Watchers,Contributors\n2026-09-23,21,16,0,7");

    expect(
      chartCsv(
        dashboardTrafficOption([
          { date: "2026-10-08", views: null, visitors: 3, clones: 1 },
          { date: "2026-10-09", views: 2, visitors: null, clones: null },
        ]),
      ),
    ).toBe("date,Views,Visitors,Clones\n2026-10-08,,3,1\n2026-10-09,2,,");
  });

  it("keeps hidden series, escapes cells, and ignores marker-only shapes", () => {
    expect(
      chartCsv({
        legend: { selected: { Views: false } },
        xAxis: { data: ["2026-09-23", null] },
        series: [
          { name: "Views", type: "line", data: ['say "hi"', { value: 5 }] },
          { name: "Note, extra", type: "line", data: ["a\nb", { color: "red" }] },
          { name: "Events", type: "line", data: [1, 2] },
          { name: "glyph", type: "scatter", data: [51, 51] },
          { type: "line", data: [9] },
          { name: "", type: "line", data: [9] },
          { name: "Broken", type: "line", data: [Number.NaN, true] },
        ],
      }),
    ).toBe('date,Views,"Note, extra",Broken\n2026-09-23,"say ""hi""","a\nb",\n,5,,');

    expect(chartCsv({ xAxis: [], series: [{ name: "Views", data: [1] }] })).toBe("date");
    expect(chartCsv({ xAxis: {} })).toBe("date");
    expect(chartCsv({ xAxis: { data: ["2026-09-23"] } })).toBe("date\n2026-09-23");
    expect(chartCsv({})).toBe("date");
    expect(chartCsv({ xAxis: [{ data: ["2026-09-23"] }], series: [{ name: "Rank", type: "line", data: [{ value: null }] }] })).toBe(
      "date,Rank\n2026-09-23,",
    );
  });

  it("downloads the drawn png and a csv file", () => {
    const downloads: { href: string; download: string }[] = [];
    vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(function (this: HTMLAnchorElement) {
      downloads.push({ href: this.href, download: this.download });
    });
    const blobs: Blob[] = [];
    vi.spyOn(URL, "createObjectURL").mockImplementation((value) => {
      blobs.push(value as Blob);
      return "blob:chart";
    });
    const revoke = vi.spyOn(URL, "revokeObjectURL").mockImplementation(() => {});

    downloadChartPng(null, "missing.png");
    expect(downloads).toEqual([]);

    const getDataURL = vi.fn(() => "data:image/png;base64,AAAA");
    downloadChartPng({ getEchartsInstance: () => ({ getDataURL }) }, "13-traffic.png");
    expect(getDataURL).toHaveBeenCalledWith({ type: "png", pixelRatio: 2, backgroundColor: "#fff" });
    expect(downloads).toEqual([{ href: "data:image/png;base64,AAAA", download: "13-traffic.png" }]);

    downloadChartCsv("13-traffic.csv", "date,Views\n2026-09-23,4");
    expect(revoke).toHaveBeenCalledWith("blob:chart");
    expect(downloads[1]?.download).toBe("13-traffic.csv");
    expect(blobs[0]?.type).toBe("text/csv;charset=utf-8");
    return blobs[0].arrayBuffer().then((buffer) => {
      const bytes = new Uint8Array(buffer);
      expect(Array.from(bytes.slice(0, 3))).toEqual([0xef, 0xbb, 0xbf]);
      expect(new TextDecoder().decode(bytes.slice(3))).toBe("date,Views\n2026-09-23,4");
    });
  });

  it("offers png and csv on the chart", async () => {
    const user = userEvent.setup();
    const downloads: string[] = [];
    vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(function (this: HTMLAnchorElement) {
      downloads.push(this.download);
    });
    vi.spyOn(URL, "createObjectURL").mockReturnValue("blob:chart");
    vi.spyOn(URL, "revokeObjectURL").mockImplementation(() => {});

    render(
      <PersistentECharts
        chartId="13:traffic"
        series={[{ key: "Views", name: "Views" }]}
        option={{
          xAxis: { data: ["2026-09-23"] },
          legend: {},
          series: [
            { name: "Views", type: "line", data: [4] },
            { name: "Events", type: "line", data: [null] },
          ],
        }}
      />,
    );

    await user.click(screen.getByRole("button", { name: "Download PNG" }));
    await user.click(screen.getByRole("button", { name: "Download CSV" }));
    expect(downloads).toEqual(["13-traffic.png", "13-traffic.csv"]);
  });
});
