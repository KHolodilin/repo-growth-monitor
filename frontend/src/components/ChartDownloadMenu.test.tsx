import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { api } from "../lib/api";
import { writeChartSelection } from "../lib/chartLegend";
import { rankHistoryOption } from "../lib/rankChart";
import { searchMetricOption } from "../lib/searchSeriesChart";
import { ChartModePlot, PersistentECharts } from "./PersistentECharts";
import { ChartCard, ChartDownloadMenu, ChartExportProvider, useChartMode, useRegisterChartExport } from "./ChartDownloadMenu";

const growthEvent = {
  id: 1,
  repositoryId: 13,
  eventAt: "2026-09-23T12:00:00Z",
  category: "PROMOTION" as const,
  type: "LINKEDIN_POST" as const,
  title: "Launch",
  source: "MANUAL" as const,
};

function stubMobile(matches: boolean) {
  const listeners = new Set<() => void>();
  const media = {
    matches,
    addEventListener: (_: string, listener: () => void) => listeners.add(listener),
    removeEventListener: (_: string, listener: () => void) => listeners.delete(listener),
  };
  vi.stubGlobal("matchMedia", vi.fn(() => media));
  return media;
}

vi.mock("../lib/api", () => ({ api: vi.fn(() => Promise.resolve([])) }));

function Registrar({ onPng, onCsv }: { onPng: () => void; onCsv: () => void }) {
  useRegisterChartExport({ downloadPng: onPng, downloadCsv: onCsv });
  return null;
}

function Host({ showChart }: { showChart: boolean }) {
  const [onPng] = useState(() => vi.fn());
  const [onCsv] = useState(() => vi.fn());
  return (
    <ChartExportProvider>
      <ChartDownloadMenu />
      {showChart ? <Registrar onPng={onPng} onCsv={onCsv} /> : null}
      <button type="button" onClick={() => onPng.mockClear()}>
        reset
      </button>
      <span data-testid="png-count">{onPng.mock.calls.length}</span>
      <span data-testid="csv-count">{onCsv.mock.calls.length}</span>
    </ChartExportProvider>
  );
}

describe("ChartDownloadMenu", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("stays hidden until a chart registers export", async () => {
    const user = userEvent.setup();
    const view = render(<ChartDownloadMenu />);
    expect(view.container).toBeEmptyDOMElement();

    view.rerender(
      <Host showChart={false} />,
    );
    expect(screen.queryByRole("button", { name: "Download" })).not.toBeInTheDocument();

    view.rerender(<Host showChart />);
    const download = await screen.findByRole("button", { name: "Download" });
    expect(download).not.toHaveTextContent("Download");
    expect(download).toHaveClass("rounded-lg", "p-1");
    expect(download.firstElementChild).toHaveClass("min-h-8", "py-1.5");
    expect(screen.getByRole("tooltip", { name: "Download" })).toHaveClass("group-hover:block");
    expect(download).toHaveAttribute("aria-expanded", "false");
    await user.click(download);
    expect(screen.queryByRole("tooltip", { name: "Download" })).not.toBeInTheDocument();
    expect(download).toHaveAttribute("aria-expanded", "true");
    await user.keyboard("a");
    expect(screen.getByRole("menu", { name: "Download format" })).toBeInTheDocument();
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();

    await user.click(download);
    await user.click(document.body);
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();

    await user.click(download);
    await user.click(download);
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Events off" }));
    await user.click(screen.getByRole("menuitemradio", { name: "GitHub events" }));
    await user.click(screen.getByRole("button", { name: "Calendar" }));
    await user.click(screen.getByRole("menuitemradio", { name: "Timeline" }));

    view.rerender(<Host showChart={false} />);
    expect(screen.queryByRole("button", { name: "Download" })).not.toBeInTheDocument();
  });

  it("downloads the chosen format and keeps the title beside the controls", async () => {
    const user = userEvent.setup();
    const onPng = vi.fn();
    const onCsv = vi.fn();
    render(
      <ChartCard title="Traffic" controls={<button type="button">Place</button>}>
        <Registrar onPng={onPng} onCsv={onCsv} />
      </ChartCard>,
    );
    const heading = screen.getByRole("heading", { name: "Traffic" });
    const row = heading.parentElement;
    expect(row?.className).toContain("flex");
    expect(row).toContainElement(screen.getByRole("button", { name: "Place" }));
    expect(row).toContainElement(screen.getByRole("button", { name: "Events off" }));
    expect(row).toContainElement(screen.getByRole("button", { name: "Calendar" }));
    expect(row).toContainElement(screen.getByRole("button", { name: "Download" }));
    expect(row?.querySelectorAll(".w-px")).toHaveLength(1);

    await user.click(screen.getByRole("button", { name: "Download" }));
    await user.click(screen.getByRole("menuitem", { name: "PNG" }));
    expect(onPng).toHaveBeenCalledOnce();
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Download" }));
    await user.click(screen.getByRole("menuitem", { name: "CSV" }));
    expect(onCsv).toHaveBeenCalledOnce();
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
  });

  it("filters events and switches the axis from the header menus", async () => {
    const user = userEvent.setup();
    render(
      <ChartCard title="Traffic">
        <Registrar onPng={vi.fn()} onCsv={vi.fn()} />
      </ChartCard>,
    );
    await user.click(screen.getByRole("button", { name: "Events off" }));
    expect(screen.getByRole("menuitemradio", { name: "Events off" })).toHaveAttribute("aria-checked", "true");
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Events off" }));
    await user.click(document.body);
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Events off" }));
    await user.click(screen.getByRole("menuitemradio", { name: "All events" }));
    expect(screen.getByRole("button", { name: "All events" })).toBeInTheDocument();
    expect(screen.getByRole("tooltip", { name: "All events" })).toHaveClass("group-hover:block");

    await user.click(screen.getByRole("button", { name: "Calendar" }));
    await user.click(screen.getByRole("menuitemradio", { name: "Timeline" }));
    expect(screen.getByRole("button", { name: "Timeline" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Timeline" }));
    await user.click(screen.getByRole("button", { name: "Timeline" }));
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
  });

  it("uses the default mode outside a card and reloads fetched events", async () => {
    const user = userEvent.setup();
    function Outside() {
      const mode = useChartMode();
      return (
        <button type="button" onClick={() => mode.openMarker([])}>
          outside
        </button>
      );
    }
    render(<Outside />);
    await user.click(screen.getByRole("button", { name: "outside" }));

    vi.mocked(api).mockRejectedValueOnce(new Error("down"));
    const failed = render(
      <ChartCard title="Traffic" repositoryId={13} onEventsChanged={vi.fn()}>
        <Registrar onPng={vi.fn()} onCsv={vi.fn()} />
      </ChartCard>,
    );
    expect(await screen.findByRole("button", { name: "Events off" })).toBeInTheDocument();
    failed.unmount();

    let fail: (error: Error) => void = () => undefined;
    vi.mocked(api).mockImplementation(
      () => new Promise((_resolve, reject) => {
        fail = reject;
      }),
    );
    const late = render(
      <ChartCard title="Traffic" repositoryId={13}>
        <Registrar onPng={vi.fn()} onCsv={vi.fn()} />
      </ChartCard>,
    );
    late.unmount();
    fail(new Error("late"));

    let release: (rows: unknown) => void = () => undefined;
    vi.mocked(api).mockImplementation(
      () => new Promise((resolve) => {
        release = resolve;
      }),
    );
    const view = render(
      <ChartCard title="Traffic" repositoryId={13}>
        <Registrar onPng={vi.fn()} onCsv={vi.fn()} />
        <PersistentECharts
          chartId="events"
          series={[{ key: "Views", name: "Views" }]}
          option={{ xAxis: { data: ["2026-09-22"] }, series: [{ name: "Views", type: "line", data: [1] }] }}
        />
      </ChartCard>,
    );
    view.unmount();
    release([]);

    vi.mocked(api).mockResolvedValue([]);
    render(
      <ChartCard title="Traffic" repositoryId={13}>
        <Registrar onPng={vi.fn()} onCsv={vi.fn()} />
        <PersistentECharts
          chartId="events"
          series={[{ key: "Views", name: "Views" }]}
          option={{ xAxis: { data: ["2026-09-22"] }, series: [{ name: "Views", type: "line", data: [1] }] }}
        />
      </ChartCard>,
    );
    await user.click(await screen.findByRole("button", { name: "mark" }));
    vi.mocked(api).mockResolvedValueOnce(undefined);
    await user.click(screen.getByRole("button", { name: "Delete" }));
    expect(await screen.findByRole("button", { name: "Events off" })).toBeInTheDocument();
  });

  it("keeps Calendar or Timeline on the chart that changed", async () => {
    const user = userEvent.setup();
    const first = render(
      <>
        <ChartCard title="Traffic" context="portfolio:traffic">
          <Registrar onPng={vi.fn()} onCsv={vi.fn()} />
        </ChartCard>
        <ChartCard title="Stats" context="portfolio:stats">
          <Registrar onPng={vi.fn()} onCsv={vi.fn()} />
        </ChartCard>
      </>,
    );
    const traffic = within(screen.getByRole("heading", { name: "Traffic" }).parentElement!.parentElement!);
    const stats = within(screen.getByRole("heading", { name: "Stats" }).parentElement!.parentElement!);
    expect(traffic.getByRole("button", { name: "Calendar" })).toHaveTextContent("Cal");
    expect(traffic.getByRole("button", { name: "Events off" })).toHaveTextContent("Off");
    expect(stats.getByRole("button", { name: "Events off" })).toHaveTextContent("Off");
    await user.click(traffic.getByRole("button", { name: "Calendar" }));
    await user.click(screen.getByRole("menuitemradio", { name: "Timeline" }));
    expect(traffic.getByRole("button", { name: "Timeline" })).toHaveTextContent("Day");
    expect(stats.getByRole("button", { name: "Calendar" })).toHaveTextContent("Cal");
    await user.click(traffic.getByRole("button", { name: "Events off" }));
    await user.click(screen.getByRole("menuitemradio", { name: "All events" }));
    expect(traffic.getByRole("button", { name: "All events" })).toHaveTextContent("All");
    expect(stats.getByRole("button", { name: "Events off" })).toHaveTextContent("Off");
    first.unmount();

    render(
      <>
        <ChartCard title="Traffic" context="portfolio:traffic">
          <Registrar onPng={vi.fn()} onCsv={vi.fn()} />
        </ChartCard>
        <ChartCard title="Stats" context="portfolio:stats">
          <Registrar onPng={vi.fn()} onCsv={vi.fn()} />
        </ChartCard>
      </>,
    );
    const restoredTraffic = within(screen.getByRole("heading", { name: "Traffic" }).parentElement!.parentElement!);
    const restoredStats = within(screen.getByRole("heading", { name: "Stats" }).parentElement!.parentElement!);
    expect(restoredTraffic.getByRole("button", { name: "Timeline" })).toHaveTextContent("Day");
    expect(restoredTraffic.getByRole("button", { name: "All events" })).toHaveTextContent("All");
    expect(restoredStats.getByRole("button", { name: "Calendar" })).toHaveTextContent("Cal");
    expect(restoredStats.getByRole("button", { name: "Events off" })).toHaveTextContent("Off");
  });

  it("uses the mobile ChartCard shell with one series and an events strip", async () => {
    const user = userEvent.setup();
    stubMobile(true);
    const onPng = vi.fn();
    const onCsv = vi.fn();
    render(
      <ChartCard
        title="Traffic"
        events={[growthEvent, { ...growthEvent, id: 2, title: "Second" }]}
        mobileSeries={[
          { key: "Views", name: "Views" },
          { key: "Visitors", name: "Visitors" },
          { key: "Clones", name: "Clones" },
        ]}
      >
        <Registrar onPng={onPng} onCsv={onCsv} />
      </ChartCard>,
    );

    expect(await screen.findByRole("button", { name: "Chart menu" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Download" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "All events" })).not.toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "Visitors" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByRole("checkbox", { name: "Events" })).not.toBeChecked();
    expect(screen.queryByRole("button", { name: /events on/ })).not.toBeInTheDocument();

    await user.click(screen.getByRole("tab", { name: "Views" }));
    expect(screen.getByRole("tab", { name: "Views" })).toHaveAttribute("aria-selected", "true");

    await user.click(screen.getByRole("button", { name: "Chart menu" }));
    await user.click(screen.getByRole("menuitemradio", { name: "Timeline" }));
    await user.click(screen.getByRole("button", { name: "Chart menu" }));
    await user.click(screen.getByRole("menuitem", { name: "PNG" }));
    expect(onPng).toHaveBeenCalledOnce();
    await user.click(screen.getByRole("button", { name: "Chart menu" }));
    await user.click(screen.getByRole("menuitem", { name: "CSV" }));
    expect(onCsv).toHaveBeenCalledOnce();
    await user.click(screen.getByRole("button", { name: "Chart menu" }));
    await user.click(screen.getByRole("menuitemradio", { name: "Calendar" }));

    await user.click(screen.getByRole("checkbox", { name: "Events" }));
    expect(screen.getByRole("button", { name: /2 events on 2026-09-23/ })).toBeInTheDocument();

    await user.click(screen.getByRole("checkbox", { name: "Events" }));
    expect(screen.queryByRole("button", { name: /events on/ })).not.toBeInTheDocument();

    await user.click(screen.getByRole("checkbox", { name: "Events" }));
    await user.click(screen.getByRole("button", { name: /2 events on 2026-09-23/ }));
    expect(await screen.findByText("Launch")).toBeInTheDocument();
  });

  it("keeps the selected mobile series and hides markers off the axis", async () => {
    const user = userEvent.setup();
    stubMobile(true);
    const series = [
      { key: "Views", name: "Views" },
      { key: "Visitors", name: "Visitors" },
    ] as const;
    const view = render(
      <ChartCard title="Traffic" events={[growthEvent]} mobileSeries={series}>
        <Registrar onPng={vi.fn()} onCsv={vi.fn()} />
        <PersistentECharts
          chartId="mobile-off-axis"
          series={series}
          option={{
            xAxis: { data: ["2026-09-22"] },
            series: [
              { name: "Views", type: "line", data: [1] },
              { name: "Visitors", type: "line", data: [0] },
            ],
          }}
        />
      </ChartCard>,
    );
    expect(await screen.findByRole("button", { name: "Chart menu" })).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "Visitors" })).toHaveAttribute("aria-selected", "true");
    await user.click(screen.getByRole("checkbox", { name: "Events" }));
    await waitFor(() => expect(screen.queryByRole("button", { name: /events on/ })).not.toBeInTheDocument());

    view.rerender(
      <ChartCard title="Traffic" events={[growthEvent]} mobileSeries={series}>
        <Registrar onPng={vi.fn()} onCsv={vi.fn()} />
        <PersistentECharts
          chartId="mobile-one-day"
          series={series}
          option={{
            xAxis: { data: ["2026-09-23"] },
            series: [
              { name: "Views", type: "line", data: [2] },
              { name: "Visitors", type: "line", data: [1] },
            ],
          }}
        />
      </ChartCard>,
    );
    expect(screen.getByRole("tab", { name: "Visitors" })).toHaveAttribute("aria-selected", "true");
    expect(await screen.findByRole("button", { name: /1 event on 2026-09-23/ })).toBeInTheDocument();

    view.rerender(
      <ChartCard title="Traffic" events={[growthEvent]} mobileSeries={[{ key: "Views", name: "Views" }]}>
        <Registrar onPng={vi.fn()} onCsv={vi.fn()} />
      </ChartCard>,
    );
    expect(await screen.findByRole("button", { name: "Chart menu" })).toBeInTheDocument();
    expect(screen.queryByRole("tablist", { name: "Chart series" })).not.toBeInTheDocument();

    view.rerender(
      <ChartCard title="Rank History" mobileSeries={[]} mobileControls={<button type="button">Place</button>}>
        <p>Select topics from the table to display rank history</p>
      </ChartCard>,
    );
    expect(screen.getByRole("button", { name: "Chart menu" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Place" })).toBeInTheDocument();
    expect(screen.getByText("Select topics from the table to display rank history")).toBeInTheDocument();
    expect(screen.queryByRole("tablist", { name: "Chart series" })).not.toBeInTheDocument();

    view.rerender(
      <ChartCard title="Traffic" events={[growthEvent]}>
        <Registrar onPng={vi.fn()} onCsv={vi.fn()} />
      </ChartCard>,
    );
    expect(screen.queryByRole("button", { name: "Chart menu" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Download" })).toBeInTheDocument();
  });

  it("defaults Visitors-only without a legend cookie and keeps stored partial selections", () => {
    const series = [
      { key: "Views", name: "Views" },
      { key: "Visitors", name: "Visitors" },
      { key: "Clones", name: "Clones" },
    ] as const;
    const first = render(
      <ChartCard title="Traffic">
        <PersistentECharts
          chartId="defaults-traffic"
          series={series}
          option={{
            legend: {},
            xAxis: { data: ["2026-09-23"] },
            series: [
              { name: "Views", type: "line", data: [1] },
              { name: "Visitors", type: "line", data: [2] },
              { name: "Clones", type: "line", data: [3] },
            ],
          }}
        />
      </ChartCard>,
    );
    expect(screen.getByRole("button", { name: "Events off" })).toBeInTheDocument();
    first.unmount();

    writeChartSelection("partial-traffic", { Views: false });
    render(
      <ChartCard title="Traffic">
        <PersistentECharts
          chartId="partial-traffic"
          series={series}
          persistSelection
          option={{
            legend: {},
            xAxis: { data: ["2026-09-23"] },
            series: [
              { name: "Views", type: "line", data: [1] },
              { name: "Visitors", type: "line", data: [2] },
              { name: "Clones", type: "line", data: [3] },
            ],
          }}
        />
        <PersistentECharts
          chartId="rank-no-persist"
          series={series}
          persistSelection={false}
          option={{ legend: {}, series: [{ name: "Views", type: "line", data: [1] }] }}
        />
      </ChartCard>,
    );
    expect(screen.getByRole("button", { name: "Events off" })).toBeInTheDocument();
  });
});


describe("Timeline mobile event regression", () => {
  afterEach(() => vi.unstubAllGlobals());
  it.each([320, 360, 390])("keeps real dates, counts and details at %ipx across axis and event toggles", async width => {
    stubMobile(true); vi.stubGlobal("innerWidth", width);
    const user = userEvent.setup();
    const dates = ["2026-10-28", "2026-10-29", "2026-10-30", "2026-10-31"];
    const events = dates.map((date, index) => ({ ...growthEvent, id: index + 10, eventAt: `${date}T12:00:00Z`, title: `Launch ${index}` }));
    const source = [...events, { ...events[1], id: 20, title: "Second launch" }, { ...events[0], id: 30, eventAt: "2026-10-27T12:00:00Z" }];
    const view = render(<ChartCard title="Traffic" events={source} context={`test:${width}`} mobileSeries={[{ key: "Visitors", name: "Visitors" }]}><PersistentECharts chartId={`timeline:${width}`} series={[{ key: "Visitors", name: "Visitors" }]} option={{ xAxis: { data: dates }, series: [{ name: "Visitors", type: "line", data: [1, null, 0, 4] }] }} /></ChartCard>);
    await user.click(screen.getByRole("checkbox", { name: "Events" }));
    expect(screen.getAllByRole("button", { name: /events? on/ })).toHaveLength(4);
    expect(screen.queryByRole("button", { name: /on 2026-10-27/ })).toBeNull();
    for (const axis of ["Timeline", "Calendar", "Timeline"]) {
      await user.click(screen.getByRole("button", { name: "Chart menu" }));
      await user.click(screen.getByRole("menuitemradio", { name: axis }));
      expect(screen.getByRole("checkbox", { name: "Events" })).toBeChecked();
      expect(screen.getAllByRole("button", { name: /events? on/ })).toHaveLength(4);
      const group = screen.getByRole("button", { name: "2 events on 2026-10-29" });
      expect(group.style.left).toContain("33.333");
      await user.click(group);
      expect(await screen.findByText("Launch 1")).toBeInTheDocument();
      expect(screen.getByText("Second launch")).toBeInTheDocument();
      await user.click(screen.getByRole("button", { name: "Close" }));
    }
    await user.click(screen.getByRole("checkbox", { name: "Events" }));
    expect(screen.queryByRole("button", { name: /events? on/ })).toBeNull();
    await user.click(screen.getByRole("checkbox", { name: "Events" }));
    expect(screen.getAllByRole("button", { name: /events? on/ })).toHaveLength(4);
    view.unmount();
    render(<ChartCard title="Empty" events={[]} context={`test:${width}`} mobileSeries={[]}><p>No plot</p></ChartCard>);
    expect(screen.getByRole("checkbox", { name: "Events" })).toBeChecked();
    expect(screen.getByText("No events in the displayed range.")).toBeInTheDocument();
  });
  it.each(["topic", "query"])("retains events with preframed %s Timeline options", async kind => {
    stubMobile(true); const user = userEvent.setup(); const dates = ["2026-09-22", "2026-09-23"];
    render(<ChartCard title="History" events={[growthEvent]} mobileSeries={[]}><ChartModePlot chartId={`sibling:${kind}`} series={[]} build={({ axis, events }) => kind === "topic" ? rankHistoryOption({ dates, axis, events, series: [{ name: "ours", limit: 50, points: dates.map(date => ({ date, position: 1 })) }] }) : searchMetricOption({ metric: "stars", dates, axis, events, missedDates: [], limit: 50, lines: [{ name: "ours", tracked: true, points: dates.map(date => ({ date, position: 1, stars: 3, forks: 0 })) }] })} /></ChartCard>);
    await user.click(screen.getByRole("checkbox", { name: "Events" }));
    await user.click(screen.getByRole("button", { name: "Chart menu" }));
    await user.click(screen.getByRole("menuitemradio", { name: "Timeline" }));
    expect(await screen.findByRole("button", { name: "1 event on 2026-09-23" })).toBeInTheDocument();
  });
});
