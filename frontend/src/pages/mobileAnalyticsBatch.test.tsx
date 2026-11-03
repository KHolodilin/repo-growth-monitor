import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { DashboardPage } from "./DashboardPage";
import { dashboardReady, repository } from "../test/fixtures";
import { NavigationButton } from "../components/NavigationButton";
import { RepositoryWarningSheet } from "../components/RepositoryWarningSheet";
import { TrafficKpiCard } from "../components/TrafficKpiCard";
import { PageBreadcrumb } from "../components/PageBreadcrumb";

const { api } = vi.hoisted(() => ({ api: vi.fn() }));
vi.mock("../lib/api", async original => ({ ...await original<typeof import("../lib/api")>(), api }));
vi.mock("echarts-for-react", async () => {
  const React = await import("react");
  return { default: React.forwardRef(function Plot({ option }: { option: { legend?: { selected?: Record<string, boolean> } } }, ref) {
    React.useImperativeHandle(ref, () => ({ getEchartsInstance: () => ({ getDataURL: () => "data:image/png;base64,AAAA" }) }));
    return <div data-testid="batch-chart" data-selected={JSON.stringify(option.legend?.selected)} />;
  }) };
});
function mobile(matches = true) { vi.stubGlobal("matchMedia", vi.fn(() => ({ matches, addEventListener: vi.fn(), removeEventListener: vi.fn() }))); }
beforeEach(() => { mobile(); api.mockReset(); });
afterEach(() => { vi.useRealTimers(); });

describe("mobile analytics batch", () => {
  it("keeps the mobile repository menu usable during viewport and page changes", async () => {
    const user = userEvent.setup();
    api.mockResolvedValue([repository]);
    render(<MemoryRouter><PageBreadcrumb items={[{ label: repository.fullName, repoSwitcher: { currentId: repository.id, hrefFor: id => `/repositories/${id}` } }]} /></MemoryRouter>);
    await user.click(screen.getByRole("button", { name: "Switch repository" }));
    fireEvent.resize(window); fireEvent.scroll(window);
    await user.type(screen.getByRole("textbox", { name: "Search repositories" }), "spring");
    expect(await screen.findByRole("option", { name: repository.name })).toBeInTheDocument();
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("listbox")).not.toBeInTheDocument();
  });
  it("never fabricates sparkline observations at missing days", () => {
    const { container, rerender } = render(<TrafficKpiCard label="Views" value={5} growthPercent={null} period="7d" preserveGaps series={[null, 1, null, 4, null]} />);
    expect(container.querySelectorAll("polyline")).toHaveLength(2);
    expect([...container.querySelectorAll("polyline")].every(node => !node.getAttribute("points")?.includes("NaN"))).toBe(true);
    rerender(<TrafficKpiCard label="Views" value={0} growthPercent={null} period="7d" preserveGaps series={[0]} />);
    expect(container.querySelector("polyline")).toHaveAttribute("points", "36,23");
  });
  it("supports context hints without treating a normal pointer click as a long press", () => {
    const onClick = vi.fn();
    const { rerender } = render(<NavigationButton label="Growth Events" mobileLabel="Events" onClick={onClick} />);
    const button = screen.getByRole("button", { name: "Growth Events" });
    fireEvent.contextMenu(button);
    fireEvent.keyDown(button, { key: "ArrowDown" });
    fireEvent.pointerDown(button);
    fireEvent.click(button); expect(onClick).toHaveBeenCalledOnce();
    rerender(<NavigationButton label="Java" onClick={onClick} />);
    fireEvent.contextMenu(screen.getByRole("button", { name: "Java" }));
  });
  it("keeps backdrop hit testing distinct from clicks inside the sheet", () => {
    const onClose = vi.fn();
    render(<RepositoryWarningSheet count={1} onClose={onClose}>Details</RepositoryWarningSheet>);
    const dialog = screen.getByRole("dialog");
    vi.spyOn(dialog, "getBoundingClientRect").mockReturnValue({ x: 10, y: 20, left: 10, top: 20, right: 200, bottom: 300, width: 190, height: 280, toJSON() {} });
    fireEvent.click(dialog, { clientX: 100, clientY: 100 }); expect(onClose).not.toHaveBeenCalled();
    fireEvent.click(dialog, { clientX: 0, clientY: 100 });
    fireEvent.click(dialog, { clientX: 201, clientY: 100 });
    fireEvent.click(dialog, { clientX: 100, clientY: 301 });
    expect(onClose).toHaveBeenCalledTimes(3);
  });
  it("retains each Portfolio metric through skeleton reloads and checks actual chart selection", async () => {
    const user = userEvent.setup();
    let release!: (value: unknown) => void;
    api.mockImplementation((path: string) => path.includes("dashboard") ? path.includes("30d") ? new Promise(resolve => { release = resolve; }) : Promise.resolve(dashboardReady) : Promise.resolve([]));
    render(<MemoryRouter><DashboardPage /></MemoryRouter>);
    await user.click(await screen.findByRole("tab", { name: "Views" }));
    await user.click(screen.getByRole("button", { name: "30d" }));
    expect(screen.queryByRole("tab", { name: "Views" })).not.toBeInTheDocument();
    await act(async () => release(dashboardReady));
    expect(await screen.findByRole("tab", { name: "Views" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByTestId("batch-chart").dataset.selected).toContain('"Views":true');
    await user.click(screen.getByRole("button", { name: "Stats" }));
    await user.click(await screen.findByRole("tab", { name: "Watchers" }));
    await user.click(screen.getByRole("button", { name: "1d" }));
    expect(await screen.findByRole("tab", { name: "Watchers" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByTestId("batch-chart").dataset.selected).toContain('"Watchers":true');
    await user.click(screen.getByRole("button", { name: "Traffic" }));
    expect(await screen.findByRole("tab", { name: "Views" })).toHaveAttribute("aria-selected", "true");
  });

  it("keeps the chosen metric for an empty period, renders missing values and suppresses comparisons", () => {
    render(<TrafficKpiCard label="Visitors" value={null} growthPercent={null} period="1d" coverage={{ observedDays: 0, expectedDays: 1 }} previousCoverage={{ observedDays: 0, expectedDays: 1 }} />);
    expect(screen.getByText("No traffic data for selected period")).toBeInTheDocument();
    expect(screen.getByText(/Comparison unavailable/)).toBeInTheDocument();
    expect(screen.queryByText(/100%/)).not.toBeInTheDocument();
  });
  it("labels partial totals and preserves true zero", () => {
    const { rerender } = render(<TrafficKpiCard label="Views" value={5} growthPercent={null} period="7d" coverage={{ observedDays: 2, expectedDays: 6 }} />);
    expect(screen.getByText("Partial data: 2 / 6 repository-days")).toBeInTheDocument();
    rerender(<TrafficKpiCard label="Views" value={0} growthPercent={-100} period="1d" coverage={{ observedDays: 1, expectedDays: 1 }} previousCoverage={{ observedDays: 1, expectedDays: 1 }} />);
    expect(screen.getByText("0")).toBeInTheDocument();
    expect(screen.getByText(/100%/)).toBeInTheDocument();
  });

  it("keeps breadcrumb navigation and complete page labels available", () => {
    render(<MemoryRouter><PageBreadcrumb items={[{ label: "Portfolio", to: "/" }, { label: "a/very-long-repository-name", repoSwitcher: { currentId: 13, hrefFor: id => `/repositories/${id}` } }, { label: "Search Visibility" }]} /></MemoryRouter>);
    expect(screen.getByRole("navigation", { name: "Breadcrumb" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Search Visibility" })).toBeInTheDocument();
    expect(screen.getByTitle("a/very-long-repository-name")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Portfolio" })).toHaveAttribute("href", "/");
  });

  it("opens full tab hints with focus/hover and suppresses navigation on a long press", () => {
    vi.useFakeTimers();
    const onClick = vi.fn();
    render(<NavigationButton label="Search Visibility" mobileLabel="Search" onClick={onClick} />);
    const button = screen.getByRole("button", { name: "Search Visibility" });
    fireEvent.focus(button); expect(screen.getByRole("tooltip")).toHaveTextContent("Search Visibility");
    fireEvent.keyDown(button, { key: "Escape" }); expect(screen.queryByRole("tooltip")).not.toBeInTheDocument();
    fireEvent.mouseEnter(button); expect(screen.getByRole("tooltip")).toBeInTheDocument();
    fireEvent.mouseLeave(button); fireEvent.blur(button);
    const pointer = new Event("pointerdown", { bubbles: true }); Object.defineProperty(pointer, "pointerType", { value: "touch" });
    fireEvent(button, pointer); act(() => vi.advanceTimersByTime(510));
    fireEvent.pointerUp(button); fireEvent.click(button); expect(onClick).not.toHaveBeenCalled();
    fireEvent.click(button); expect(onClick).toHaveBeenCalledOnce();
    fireEvent.pointerCancel(button);
  });
  it("only gives language hints when the full label is actually clipped and updates on resize", () => {
    let measure!: () => void;
    vi.stubGlobal("ResizeObserver", class { constructor(callback: () => void) { measure = callback; } observe() {} disconnect() {} });
    render(<NavigationButton label="TypeScript" truncate />);
    const button = screen.getByRole("button", { name: "TypeScript" });
    fireEvent.focus(button); expect(screen.queryByRole("tooltip")).not.toBeInTheDocument();
    const label = within(button).getByText("TypeScript");
    Object.defineProperty(label, "scrollWidth", { value: 150, configurable: true });
    Object.defineProperty(label, "clientWidth", { value: 80, configurable: true });
    act(measure); expect(screen.getByRole("tooltip")).toHaveTextContent("TypeScript");
    Object.defineProperty(label, "clientWidth", { value: 180 });
    act(measure); expect(screen.queryByRole("tooltip")).not.toBeInTheDocument();
  });
  it("retains full desktop tab labels without redundant hints", () => {
    mobile(false);
    render(<NavigationButton label="Topics Visibility" mobileLabel="Topics" />);
    fireEvent.focus(screen.getByRole("button", { name: "Topics Visibility" }));
    expect(screen.getByText("Topics Visibility")).toBeInTheDocument();
    expect(screen.queryByRole("tooltip")).not.toBeInTheDocument();
  });

  it("closes the warning sheet via button, cancellation, backdrop and deliberate header swipe", () => {
    const onClose = vi.fn();
    const { unmount } = render(<RepositoryWarningSheet count={2} onClose={onClose}><a href="/repositories/13">owner/repository</a></RepositoryWarningSheet>);
    const dialog = screen.getByRole("dialog", { name: "Repository data warnings" });
    expect(dialog).toHaveAttribute("aria-modal", "true");
    expect(document.body.style.overflow).toBe("hidden");
    fireEvent.click(screen.getByRole("button", { name: "Close repository warnings" }));
    fireEvent(dialog, new Event("cancel", { bubbles: true, cancelable: true }));
    fireEvent.click(dialog, { clientY: -5 });
    const header = screen.getByRole("heading").parentElement!.parentElement!;
    const pointer = (type: string, x: number, y: number) => { const e = new Event(type, { bubbles: true }); Object.defineProperties(e, { clientX: { value: x }, clientY: { value: y } }); fireEvent(header, e); };
    pointer("pointerdown", 10, 0); pointer("pointerup", 10, 100);
    expect(onClose).toHaveBeenCalledTimes(4);
    pointer("pointerdown", 10, 0); pointer("pointerup", 150, 100);
    fireEvent.pointerCancel(header); pointer("pointerup", 10, 100);
    fireEvent.click(screen.getByRole("link"));
    expect(onClose).toHaveBeenCalledTimes(4);
    unmount(); expect(document.body.style.overflow).toBe("");
  });

  it("opens the mobile Portfolio sheet with real warning links and closes on navigation", async () => {
    const user = userEvent.setup();
    api.mockImplementation((path: string) => Promise.resolve(path.includes("dashboard") ? { ...dashboardReady, repositories: [{ ...dashboardReady.repositories[0], trafficNotice: { reason: "GITHUB_DATA_MISSING", date: "2026-11-02", lastTrafficDate: "2026-11-01" } }] } : []));
    render(<MemoryRouter><DashboardPage /></MemoryRouter>);
    await user.click(await screen.findByRole("button", { name: /1 repository/ }));
    const dialog = screen.getByRole("dialog");
    const link = within(dialog).getByRole("link");
    expect(link).toHaveTextContent(dashboardReady.repositories[0].fullName);
    await user.click(link);
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(document.body.style.overflow).toBe("");
  });
});
