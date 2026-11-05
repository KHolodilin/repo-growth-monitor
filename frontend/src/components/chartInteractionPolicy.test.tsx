import { MemoryRouter } from "react-router-dom";
import { CompetitorCardView } from "../pages/CompetitorCardPage";
import { publishChartVisibility } from "../lib/chartVisibility";
import { SnapshotHistoryView } from "./SnapshotHistoryView";
import { snapshotHistory } from "../test/fixtures";
import { render, screen, fireEvent } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { PersistentECharts } from "./PersistentECharts";
import { ChartCard } from "./ChartDownloadMenu";
const { dispatchAction } = vi.hoisted(() => ({ dispatchAction: vi.fn() }));
vi.mock("echarts-for-react", async () => { const { forwardRef, useImperativeHandle } = await import("react"); return ({ default: forwardRef(({ option }: any, ref) => { useImperativeHandle(ref, () => ({ getEchartsInstance: () => ({ dispatchAction }) })); return <pre data-testid="interaction-option">{JSON.stringify(option)}</pre>; }) }); });
const series = [{ key: "repository:1", name: "A very long repository name with no-shortcuts" }];
const option = { xAxis: { data: ["2026-11-04"] }, series: [{ name: series[0].name, type: "line", data: [0] }] };
describe("shared legend and tooltip lifecycle", () => {
  it("makes previously static detail legends keyboard operable and retains hidden entries across periods", async () => {
    const user = userEvent.setup();
    const view = render(<ChartCard title="History" period="7d"><PersistentECharts chartId="policy-detail" series={series} option={option} persistSelection={false} /></ChartCard>);
    const toggle = screen.getByRole("button", { name: series[0].name });
    expect(toggle).toHaveAttribute("aria-pressed", "true");
    toggle.focus(); await user.keyboard(" ");
    expect(toggle).toHaveAttribute("aria-pressed", "false");
    expect(screen.getByRole("status")).toHaveTextContent("No series selected");
    view.rerender(<ChartCard title="History" period="30d"><PersistentECharts chartId="policy-detail" series={series} option={option} persistSelection={false} /></ChartCard>);
    expect(toggle).toHaveAttribute("aria-pressed", "false");
    await user.click(toggle); expect(toggle).toHaveAttribute("aria-pressed", "true");
    dispatchAction.mockClear();
    fireEvent.pointerDown(toggle); expect(dispatchAction).not.toHaveBeenCalled();
    fireEvent.pointerDown(document.body); expect(dispatchAction).toHaveBeenCalledWith({ type: "hideTip" });
    dispatchAction.mockClear(); fireEvent.scroll(window); expect(dispatchAction).toHaveBeenCalled();
    dispatchAction.mockClear(); fireEvent.resize(window); expect(dispatchAction).toHaveBeenCalled();
    dispatchAction.mockClear(); view.unmount(); fireEvent.pointerDown(document.body); expect(dispatchAction).not.toHaveBeenCalled();
  });
  it("keeps a single mobile series legend interactive even without a selector", async () => {
    vi.stubGlobal("matchMedia", vi.fn(() => ({ matches: true, addEventListener: vi.fn(), removeEventListener: vi.fn() })));
    const user = userEvent.setup();
    render(<ChartCard title="History" mobileSeries={series}><PersistentECharts chartId="policy-mobile" series={series} option={option} persistSelection={false} /></ChartCard>);
    const toggle = await screen.findByRole("button", { name: series[0].name }); await user.click(toggle);
    expect(toggle).toHaveAttribute("aria-pressed", "false");
    expect(JSON.parse(screen.getByTestId("interaction-option").textContent!).legend.selected[series[0].name]).toBe(false);
    vi.unstubAllGlobals();
  });
  it("keeps History legend, snapshot checkbox and source picker in sync", async () => {
    vi.stubGlobal("matchMedia", vi.fn(() => ({ matches: true, addEventListener: vi.fn(), removeEventListener: vi.fn() })));
    const user = userEvent.setup();
    render(<SnapshotHistoryView title="History" kind="referrers" history={snapshotHistory} />);
    const name = snapshotHistory.rows[0].key;
    const legend = await screen.findByRole("button", { name });
    const checkbox = await screen.findByRole("checkbox", { name: `Show ${name} on the chart` });
    await user.click(legend); expect(checkbox).not.toBeChecked();
    await user.click(checkbox); expect(legend).toHaveAttribute("aria-pressed", "true");
    await user.click(legend);
    await user.click(screen.getByRole("button", { name: /Select sources|more sources/ }));
    const picker = screen.getByRole("checkbox", { name });
    expect(picker).not.toBeChecked(); await user.click(picker);
    expect(legend).toHaveAttribute("aria-pressed", "true"); expect(checkbox).toBeChecked();
    vi.unstubAllGlobals();
  });

  it("restores a previously hidden candidate when mobile activates its one-series control", async () => {
    vi.stubGlobal("matchMedia", vi.fn(() => ({ matches: true, addEventListener: vi.fn(), removeEventListener: vi.fn() })));
    publishChartVisibility("policy-activation", { "repository:1": false });
    render(<ChartCard title="History" mobileSeries={[...series, { key: "repository:2", name: "Other repo" }]}><PersistentECharts chartId="policy-activation" series={series} option={option} persistSelection={false} /></ChartCard>);
    expect(await screen.findByRole("tab", { name: series[0].name })).toHaveAttribute("aria-selected", "true");
    expect(JSON.parse(screen.getByTestId("interaction-option").textContent!).legend.selected[series[0].name]).toBe(true);
    vi.unstubAllGlobals();
  });

  it("uses parent collection dates rather than a competitor falling out of results", () => {
    const yesterday = new Date(Date.now() - 86400000).toISOString().slice(0,10);
    const earlier = new Date(Date.now() - 2 * 86400000).toISOString().slice(0,10);
    const card = { githubRepositoryId: 1, fullName: "competitor", description: null, htmlUrl: "https://github.com/a/b", topics: [], discoverability: [], points: [{ date: earlier, stars: 2, forks: 0 }] };
    const view = render(<MemoryRouter><CompetitorCardView items={[]} card={card} chartId="policy-competitor" repositoryId={13} collectionDates={[yesterday]} /></MemoryRouter>);
    expect(screen.getByTestId("interaction-option")).not.toHaveTextContent("Collection warning");
    view.rerender(<MemoryRouter><CompetitorCardView items={[]} card={card} chartId="policy-competitor" repositoryId={13} collectionDates={[earlier]} missedCollectionDates={[yesterday]} /></MemoryRouter>);
    expect(screen.getByTestId("interaction-option")).toHaveTextContent("Collection warning");
  });

});
