import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ChartCard, useRegisterChartExport } from "./ChartDownloadMenu";
import { PersistentECharts } from "./PersistentECharts";

function Registrar() {
  useRegisterChartExport({ downloadPng: vi.fn(), downloadCsv: vi.fn() });
  return null;
}

function stubMobile(matches: boolean) {
  const media = {
    matches,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  };
  vi.stubGlobal("matchMedia", vi.fn(() => media));
}

describe("PersistentECharts mobile shell", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("keeps one series, publishes axis dates, and opens a single-event marker", async () => {
    const user = userEvent.setup();
    stubMobile(true);
    render(
      <ChartCard
        title="Traffic"
        events={[
          {
            id: 1,
            repositoryId: 13,
            eventAt: "2026-09-23T12:00:00Z",
            category: "PROMOTION",
            type: "LINKEDIN_POST",
            title: "Solo",
            source: "MANUAL",
          },
        ]}
        mobileSeries={[
          { key: "Views", name: "Views" },
          { key: "Visitors", name: "Visitors" },
        ]}
      >
        <Registrar />
        <PersistentECharts
          chartId="mobile-plot"
          series={[
            { key: "Views", name: "Views" },
            { key: "Visitors", name: "Visitors" },
          ]}
          option={{
            xAxis: { data: ["2026-09-22", "2026-09-23"] },
            series: [
              { name: "Views", type: "line", data: [1, 2] },
              { name: "Visitors", type: "line", data: [0, 1] },
            ],
          }}
          style={{ height: 360 }}
        />
      </ChartCard>,
    );

    expect(await screen.findByRole("button", { name: "Chart menu" })).toBeInTheDocument();
    expect(screen.getByTestId("echart").parentElement).toHaveClass("-mx-4");
    expect(screen.getByRole("tab", { name: "Visitors" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByRole("checkbox", { name: "Events" })).not.toBeChecked();
    await user.click(screen.getByRole("checkbox", { name: "Events" }));
    await waitFor(() =>
      expect(screen.getByRole("button", { name: /1 event on 2026-09-23/ })).toBeInTheDocument(),
    );
    await user.click(screen.getByRole("tab", { name: "Views" }));
    expect(screen.getByRole("tab", { name: "Views" })).toHaveAttribute("aria-selected", "true");
    await user.click(screen.getByRole("button", { name: /1 event on 2026-09-23/ }));
    expect(await screen.findByText("Solo")).toBeInTheDocument();
  });

  it("hides legend selection when the option has a legend object", async () => {
    stubMobile(true);
    render(
      <ChartCard title="Traffic" mobileSeries={[{ key: "Views", name: "Views" }]}>
        <Registrar />
        <PersistentECharts
          chartId="mobile-legend"
          series={[{ key: "Views", name: "Views" }]}
          option={{
            legend: { data: ["Views"] },
            xAxis: { data: ["2026-09-23"] },
            series: [{ name: "Views", type: "line", data: [1] }],
          }}
        />
      </ChartCard>,
    );
    expect(await screen.findByRole("button", { name: "Chart menu" })).toBeInTheDocument();
    expect(screen.getByTestId("echart").parentElement).toHaveClass("-mx-4");
  });

  it("keeps desktop plot insets", () => {
    stubMobile(false);
    render(
      <ChartCard title="Traffic" mobileSeries={[{ key: "Views", name: "Views" }]}>
        <PersistentECharts chartId="desktop-insets" series={[]} option={{ series: [] }} />
      </ChartCard>,
    );
    expect(screen.getByTestId("echart").parentElement).not.toHaveClass("-mx-4");
  });

  it("clears axis dates when the chart has no category axis", async () => {
    stubMobile(true);
    render(
      <ChartCard title="Traffic" mobileSeries={[{ key: "Views", name: "Views" }]} events={[]}>
        <Registrar />
        <PersistentECharts
          chartId="mobile-empty-axis"
          series={[{ key: "Views", name: "Views" }]}
          option={{ series: [{ name: "Views", type: "line", data: [1] }] }}
        />
      </ChartCard>,
    );
    expect(await screen.findByRole("button", { name: "Chart menu" })).toBeInTheDocument();
    expect(screen.getByTestId("echart").parentElement).toHaveClass("-mx-4");
    expect(screen.queryByLabelText("Growth events")).not.toBeInTheDocument();
  });
});
