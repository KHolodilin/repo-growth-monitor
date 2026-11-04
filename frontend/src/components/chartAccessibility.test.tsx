import { act, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { afterEach, describe, expect, it, vi } from "vitest";
import { PersistentECharts } from "./PersistentECharts";
import { ChartCard } from "./ChartDownloadMenu";
import { RankSummary } from "./RankSummary";
import { TrafficKpiCard } from "./TrafficKpiCard";
import { ChartDataAlternative } from "./ChartDataAlternative";
import { SeriesSwatch } from "./SeriesSwatch";
import { REPO_TRAFFIC_SERIES, TRAFFIC_SERIES, STATS_SERIES, writeChartSelection } from "../lib/chartLegend";
import { dashboardTrafficOption } from "../lib/dashboardChart";
import { statsChartOption } from "../lib/statsChart";
import { metricColor, prepareSeriesStyles, seriesStyle } from "../lib/seriesStyle";
vi.mock("echarts-for-react", async () => {
  const React = await import("react");
  return { default: React.forwardRef(({ option }: { option: unknown }, ref) => {
    React.useImperativeHandle(ref, () => ({ getEchartsInstance: () => ({ getDataURL: () => "data:image/png;base64,AAAA" }) }));
    return <div data-testid="chart-option">{JSON.stringify(option)}</div>;
  }) };
});
const option = () => dashboardTrafficOption([{ date: "2026-11-01", views: 12, visitors: 3, clones: 10, uniqueCloners: 2 }, { date: "2026-11-02", views: 0, visitors: null, clones: null, uniqueCloners: 0 }]);
function graph() { return JSON.parse(screen.getByTestId("chart-option").textContent!); }
afterEach(() => vi.unstubAllGlobals());
describe("accessible chart controls and compact summaries", () => {
  it("toggles multiple desktop series with native keyboard controls and exports the same state", async () => {
    const user = userEvent.setup();
    const view = render(<PersistentECharts chartId="dashboard:traffic" accessibleTitle="Portfolio Traffic" series={TRAFFIC_SERIES} option={option()} />);
    const group = screen.getByRole("group", { name: "Portfolio Traffic series" });
    const views = within(group).getByRole("button", { name: "Views" });
    const visitors = within(group).getByRole("button", { name: "Visitors" });
    expect(visitors).toHaveAttribute("aria-pressed", "true"); expect(views).toHaveAttribute("aria-pressed", "false");
    await user.tab(); expect(views).toHaveFocus(); await user.keyboard(" ");
    expect(views).toHaveAttribute("aria-pressed", "true"); expect(graph().legend.selected).toMatchObject({ Views: true, Visitors: true });
    await user.tab(); await user.keyboard("{Enter}"); expect(visitors).toHaveAttribute("aria-pressed", "false");
    expect(graph().legend.show).toBe(false);
    expect(screen.getByRole("img", { name: "Portfolio Traffic graph" })).toHaveAccessibleDescription(/Views/);
    view.rerender(<PersistentECharts chartId="dashboard:traffic" accessibleTitle="Portfolio Traffic" series={TRAFFIC_SERIES} option={option()} />);
    expect(views).toHaveAttribute("aria-pressed", "true");
    for (const button of within(group).getAllByRole("button")) if (button.getAttribute("aria-pressed") === "true") await user.click(button);
    expect(screen.getByRole("status")).toHaveTextContent("No series selected");
    await user.click(screen.getByText("View historical data"));
    const table = screen.getByRole("table", { name: "Portfolio Traffic historical values" });
    expect(table).toHaveTextContent("2026-11-02"); expect(table).toHaveTextContent("0"); expect(table).toHaveTextContent("—");
    expect(within(table).getAllByRole("columnheader")).toHaveLength(5);
  });
  it("preserves the meaning of legacy repository Clones preferences and selects the new total independently", async () => {
    const user = userEvent.setup(); writeChartSelection("13:traffic", { Views: false, Visitors: false, Clones: true });
    const view = render(<PersistentECharts chartId="13:traffic" accessibleTitle="Repository Traffic" series={REPO_TRAFFIC_SERIES} option={option()} />);
    expect(screen.getByRole("button", { name: "Unique Cloners" })).toHaveAttribute("aria-pressed", "true");
    const clones = screen.getByRole("button", { name: "Clones" }); expect(clones).toHaveAttribute("aria-pressed", "false");
    await user.click(clones); view.unmount();
    render(<PersistentECharts chartId="13:traffic" accessibleTitle="Repository Traffic" series={REPO_TRAFFIC_SERIES} option={option()} dataAlternative={false} />);
    expect(screen.getByRole("button", { name: "Clones" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("button", { name: "Unique Cloners" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.queryByText("View historical data")).toBeNull();
  });
  it("switches responsive controls without hidden desktop focus targets and exposes four full metric names", async () => {
    const user = userEvent.setup(); let mobile = true; const listeners: (() => void)[] = [];
    vi.stubGlobal("matchMedia", vi.fn(() => ({ get matches() { return mobile; }, addEventListener: (_: string, callback: () => void) => listeners.push(callback), removeEventListener: vi.fn() })));
    render(<MemoryRouter><ChartCard title="Traffic" mobileSeries={TRAFFIC_SERIES}><PersistentECharts chartId="mobile-test" accessibleTitle="Portfolio Traffic" series={TRAFFIC_SERIES} option={option()} /></ChartCard></MemoryRouter>);
    expect(await screen.findByRole("tab", { name: "Unique Cloners" })).toBeInTheDocument();
    expect(screen.queryByRole("group", { name: "Portfolio Traffic series" })).toBeNull();
    await user.click(screen.getByRole("tab", { name: "Unique Cloners" }));
    expect(graph().legend.selected).toMatchObject({ Views: false, Visitors: false, Clones: false, "Unique Cloners": true });
    act(() => { mobile = false; listeners.forEach(callback => callback()); });
    expect(screen.queryByRole("tablist")).toBeNull();
    expect(screen.getByRole("group", { name: "Portfolio Traffic series" })).toBeInTheDocument();
  });
  it("uses the same metric identity in KPI, actual Stats options and aliases, independent of trend direction", () => {
    const stats = statsChartOption([{ date: "2026-11-01", stars: 1, forks: 2, watchers: 3, contributors: 4 }]);
    for (const name of ["Views", "Visitors", "Clones", "Unique Cloners", "Stars", "Forks", "Watchers", "Contributors", "Unique Contributors"]) {
      const view = render(<TrafficKpiCard label={name} value={1} growthPercent={-50} period="7d" series={[2, 1]} />);
      const stroke = view.container.querySelector("polyline")!.getAttribute("stroke");
      expect(stroke).toBe(metricColor(name));
      const line = stats.series.find(item => item.name === (name === "Unique Contributors" ? "Contributors" : name));
      if (line) expect(stroke).toBe(line.itemStyle.color);
      view.unmount();
    }
    expect(STATS_SERIES.map(item => seriesStyle(item.name).color)).toEqual(stats.series.map(item => item.itemStyle.color));
  });
  it("renders the compact mobile hierarchy without losing zero, missing and large values", () => {
    vi.stubGlobal("matchMedia", vi.fn(() => ({ matches: true, addEventListener: vi.fn(), removeEventListener: vi.fn() })));
    const view = render(<RankSummary current={null} best={1} limit={50} change7d={0} change30d={null} total={123456789} />);
    expect(screen.getByLabelText("Rank summary")).toBeInTheDocument(); expect(screen.getByText(">50")).toBeInTheDocument(); expect(screen.getByText("No change")).toBeInTheDocument(); expect(screen.getByText("—")).toBeInTheDocument();
    expect(screen.getByText("Current Rank").compareDocumentPosition(screen.getByText("Best Rank")) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    view.rerender(<RankSummary current={4} best={2} limit={50} change7d={3} change30d={-2} total={null} />);
    expect(screen.getByText("↑3")).toBeInTheDocument(); expect(screen.getByText("↓2")).toBeInTheDocument(); expect(screen.getByText("Total Results")).toBeInTheDocument();
  });
  it("offers an empty historical alternative and readable pattern/marker swatches", async () => {
    const user = userEvent.setup(); prepareSeriesStyles(Array.from({ length: 170 }, (_, i) => `test:large-${i}`));
    render(<><ChartDataAlternative option={{}} title="Empty" />{Array.from({ length: 170 }, (_, i) => <SeriesSwatch key={i} style={seriesStyle(`test:large-${i}`)} />)}</>);
    await user.click(screen.getByText("View historical data")); expect(screen.getByText("No observations for this period.")).toBeVisible();
  });
  it("keeps dynamic mobile selector marks tied to keys rather than their reordered labels", async () => {
    vi.stubGlobal("matchMedia", vi.fn(() => ({ matches: true, addEventListener: vi.fn(), removeEventListener: vi.fn() })));
    const user = userEvent.setup();
    const options = [{ key: "query:selector-a", name: "First query" }, { key: "query:selector-b", name: "Second query" }];
    render(<MemoryRouter><ChartCard title="Ranks" mobileSeries={options}><div>History</div></ChartCard></MemoryRouter>);
    const second = await screen.findByRole("tab", { name: "Second query" });
    await user.click(second); expect(second).toHaveAttribute("aria-selected", "true");
  });

});
