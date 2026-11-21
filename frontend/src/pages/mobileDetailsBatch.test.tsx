import { act, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Link, Route, Routes, useNavigate } from "react-router-dom";
import { beforeEach, afterEach, describe, expect, it, vi } from "vitest";
import { SearchResultsTable } from "../components/SearchResultsTable";
import { TopicResultsTable } from "../components/TopicResultsTable";
import { SnapshotHistoryView } from "../components/SnapshotHistoryView";
import { SnapshotHistoryTable } from "../components/SnapshotHistoryTable";
import { ServicePathToggle } from "../components/ServicePaths";
import { RouteScroll } from "../components/RouteScroll";
import { GrowthEventSettingsCard } from "../components/GrowthEventSettingsCard";
import { GrowthEventsPanel } from "../components/GrowthEventsPanel";
import { SearchVisibilityKpis } from "../components/SearchVisibilityKpis";
import { RepositoryDetailsPage } from "./RepositoryDetailsPage";
import { TrafficHistoryPage } from "./TrafficHistoryPage";
import { QueryDetailsPage } from "./QueryDetailsPage";
import { TopicDetailsPage } from "./TopicDetailsPage";
import { repository, traffic, searchHistory, searchResults, snapshotHistory, topicHistory, topicResults } from "../test/fixtures";
import { anonymousMe } from "../lib/auth";
const { api, auth } = vi.hoisted(() => ({ api: vi.fn(), auth: { me: { authenticated: false, actions: [] as string[] }, loading: false } }));
vi.mock("../lib/api", async original => ({ ...await original<typeof import("../lib/api")>(), api }));
vi.mock("../lib/AuthContext", () => ({ useAuth: () => auth }));
let mobile = true;
let listeners: (() => void)[] = [];
function respond(url: string) {
  if (url.includes("traffic?")) return traffic;
  if (url.includes("topics/") && url.includes("/history")) return topicHistory;
  if (url.includes("topics/") && url.includes("/results")) return topicResults;
  if (url.includes("topics/") && url.includes("/series")) return [{ githubRepositoryId: 2, fullName: "big/outbox", points: [{ date: "2026-09-23", position: 1 }] }];
  if (url.includes("/admin/repositories/13") && !url.slice(url.indexOf("/13") + 3).startsWith("/")) return repository;
  return [];
}
function router(ui: React.ReactNode) { return render(<MemoryRouter>{ui}</MemoryRouter>); }
beforeEach(() => {
  mobile = true; listeners = [];
  auth.me = { ...anonymousMe }; auth.loading = false;
  api.mockReset(); api.mockImplementation(async (url: string) => respond(String(url)));
  vi.stubGlobal("matchMedia", vi.fn(() => ({ get matches() { return mobile; }, addEventListener: (_: string, cb: () => void) => listeners.push(cb), removeEventListener: vi.fn() })));
});
afterEach(() => vi.unstubAllGlobals());

describe("batch 178–182", () => {
  it("renders Search cards, separates navigation/selection, sorts, and changes responsive presentation", async () => {
    const user = userEvent.setup(); const toggle = vi.fn();
    router(<SearchResultsTable rows={searchResults.rows} repositoryId={13} trackedGithubId={99} onToggle={toggle} selectedIds={[1]} nameTo={row => `/competitor/${row.result.githubRepositoryId}`} />);
    expect(screen.queryByRole("table")).toBeNull();
    expect(screen.getByRole("link", { name: "other/repo" })).toHaveAttribute("href", "/competitor/1");
    const checkbox = screen.getByRole("checkbox", { name: "Show other/repo on the chart" });
    expect(checkbox).toBeChecked(); await user.click(checkbox); expect(toggle).toHaveBeenCalledWith(1);
    expect(screen.queryByRole("checkbox", { name: `Show ${repository.fullName} on the chart` })).toBeNull();
    expect(screen.getByText("Tracked repository")).toBeInTheDocument();
    await user.selectOptions(screen.getByRole("combobox", { name: "Sort by" }), "stars");
    await user.click(screen.getByRole("button", { name: "Reverse sort direction" }));
    expect(screen.getAllByRole("article")[0]).toHaveTextContent(repository.fullName);
    act(() => { mobile = false; listeners.forEach(cb => cb()); });
    expect(screen.getByRole("table")).toBeInTheDocument(); expect(screen.queryByRole("article")).toBeNull();
  });
  it("handles Topic selection on both layouts, missing metadata, fallback links and empty cards", async () => {
    const user = userEvent.setup(); const toggle = vi.fn();
    const rows = [{ ...topicResults.rows[0], result: { ...topicResults.rows[0].result, language: undefined, htmlUrl: undefined } }, topicResults.rows[1]];
    const ui = (data = rows, selected = [2]) => <TopicResultsTable rows={data} repositoryId={13} trackedGithubId={99} onToggle={toggle} selectedIds={selected} />;
    const view = router(ui());
    expect(screen.getByRole("link", { name: "big/outbox" })).toHaveAttribute("href", "https://github.com/big/outbox");
    await user.click(screen.getByRole("checkbox", { name: "Show big/outbox on the chart" })); expect(toggle).toHaveBeenCalledWith(2);
    await user.selectOptions(screen.getByRole("combobox", { name: "Sort by" }), "language");
    view.rerender(<MemoryRouter>{ui([], [])}</MemoryRouter>); expect(screen.getByText("No results in this snapshot.")).toBeInTheDocument();
    view.rerender(<MemoryRouter>{ui()}</MemoryRouter>);
    act(() => { mobile = false; listeners.forEach(cb => cb()); });
    await user.click(screen.getByRole("checkbox", { name: "Show big/outbox on the chart" })); expect(toggle).toHaveBeenCalledTimes(2);
    expect(screen.queryByRole("article")).toBeNull();
  });
  it("preserves tracked-position highlighting and non-selectable shared consumers", () => {
    router(<><SearchResultsTable rows={searchResults.rows} repositoryId={13} trackedPosition={4} /><TopicResultsTable rows={topicResults.rows} repositoryId={13} /></>);
    expect(screen.getByText("Tracked repository")).toBeInTheDocument(); expect(screen.queryByRole("checkbox")).toBeNull();
  });
  it("navigates actual snapshot dates without altering series selection and retains zero/missing values", async () => {
    const user = userEvent.setup();
    const extra = { ...snapshotHistory.rows[0], key: "/very/long/path/with/no-clipping", title: "Path title", cells: snapshotHistory.rows[0].cells.map((cell, i) => ({ ...cell, visitors: i ? 0 : null, visitorsDelta: i ? -2 : null, views: i ? null : 4, viewsDelta: null, firstSeen: false })) };
    router(<SnapshotHistoryView title="Path history" kind="paths" history={{ ...snapshotHistory, rows: [extra] }} />);
    const select = screen.getByRole("combobox", { name: "Snapshot date" });
    expect(select).toHaveValue(snapshotHistory.dates.at(-1));
    expect(screen.getByRole("button", { name: "Next snapshot" })).toBeDisabled();
    const card = screen.getByRole("article"); expect(card).toHaveTextContent("0"); expect(card).toHaveTextContent("↓2"); expect(card).toHaveTextContent("—");
    const check = screen.getByRole("checkbox", { name: `Show ${extra.key} on the chart` });
    expect(check).toBeChecked(); await user.click(check); expect(check).not.toBeChecked();
    await user.click(screen.getByRole("button", { name: "Previous snapshot" }));
    expect(screen.getByRole("button", { name: "Previous snapshot" })).toBeDisabled(); expect(check).not.toBeChecked();
    await user.selectOptions(select, snapshotHistory.dates.at(-1)!);
    await user.click(screen.getByRole("button", { name: "Previous snapshot" }));
    await user.click(screen.getByRole("button", { name: "Next snapshot" }));
    await user.click(check); expect(check).toBeChecked();
  });
  it("distinguishes no selection, unavailable chart history, refreshed dates, and a single/empty snapshot", async () => {
    const user = userEvent.setup(); const emptyRow = { ...snapshotHistory.rows[0], cells: snapshotHistory.rows[0].cells.map(cell => ({ ...cell, visitors: null, views: null })) };
    const view = router(<SnapshotHistoryView title="History" kind="referrers" history={{ ...snapshotHistory, rows: [emptyRow] }} />);
    expect(screen.getByText("No data for the selected period.")).toBeInTheDocument();
    await user.click(screen.getByRole("checkbox", { name: "Show github.com on the chart" }));
    expect(screen.getByText(/No sources selected/)).toBeInTheDocument();
    view.rerender(<MemoryRouter><SnapshotHistoryView title="History" kind="referrers" history={{ ...snapshotHistory, dates: ["2026-09-25"], rows: [{ ...emptyRow, cells: [] }] }} /></MemoryRouter>);
    expect(screen.getByRole("combobox", { name: "Snapshot date" })).toHaveValue("2026-09-25");
    expect(screen.getByRole("button", { name: "Previous snapshot" })).toBeDisabled(); expect(screen.getByRole("button", { name: "Next snapshot" })).toBeDisabled();
    view.rerender(<MemoryRouter><SnapshotHistoryTable kind="paths" history={{ ...snapshotHistory, dates: [], rows: [] }} /></MemoryRouter>);
    expect(screen.getByText("No snapshots for this period.")).toBeInTheDocument();
  });
  it("supports mobile snapshot display without chart controls and a missing cell", () => {
    router(<SnapshotHistoryTable kind="referrers" history={{ ...snapshotHistory, rows: [{ ...snapshotHistory.rows[0], cells: [] }] }} />);
    expect(screen.queryByRole("checkbox")).toBeNull(); expect(screen.getByRole("article")).toHaveTextContent("—");
  });
  it("keeps the restricted-page explanation touch/keyboard accessible", async () => {
    const user = userEvent.setup(); const change = vi.fn();
    router(<ServicePathToggle checked={false} onChange={change} />);
    await user.click(screen.getByRole("checkbox", { name: "Show restricted pages" })); expect(change).toHaveBeenCalledWith(true);
    await user.click(screen.getByText("?"));
    expect(screen.getByText(/Include pages restricted to repository owners/)).toBeVisible();
    expect(screen.getByText(/Public pages like Issues and Pull Requests remain visible/)).toBeVisible();
  });
  it.each([false, true])("shows read-only event configuration for authenticated=%s", async authenticated => {
    auth.me = { ...anonymousMe, authenticated, actions: [] };
    api.mockResolvedValue([{ eventType: "STAR_MILESTONE", enabled: true }, { eventType: "FORK_MILESTONE", enabled: false }]);
    router(<><GrowthEventSettingsCard repositoryId={13} /><GrowthEventsPanel repositoryId={13} events={[]} onChanged={vi.fn()} /><SearchVisibilityKpis queries={[]} period="7d" /></>);
    expect(await screen.findByText(/Current configuration \(1 enabled\)/)).toBeInTheDocument();
    expect(screen.queryByRole("checkbox")).toBeNull(); expect(screen.queryByText(/Choose which/)).toBeNull(); expect(screen.queryByText(/^\d+ available$/)).toBeNull();
    expect(screen.getByText(/An administrator can configure collection/)).toBeInTheDocument();
    await userEvent.click(screen.getByText(/Current configuration/));
    expect(screen.getByText("Enabled")).toBeVisible(); expect(screen.getByText("Disabled")).toBeVisible(); expect(screen.getAllByText("Not available").length).toBeGreaterThan(0);
  });
  it("changes admin instructions immediately when access changes and handles read-only load failure", async () => {
    auth.me = { ...anonymousMe, authenticated: true, actions: ["MUTATE"] }; api.mockResolvedValue([]);
    const view = router(<><GrowthEventSettingsCard repositoryId={13} /><GrowthEventsPanel repositoryId={13} events={[]} onChanged={vi.fn()} /></>);
    expect(screen.getByText(/Choose which automatic events/)).toBeInTheDocument(); expect(screen.getByRole("button", { name: "+ Add event" })).toBeInTheDocument();
    auth.me = { ...anonymousMe }; view.rerender(<MemoryRouter><GrowthEventSettingsCard repositoryId={13} /></MemoryRouter>);
    expect(screen.queryByRole("checkbox")).toBeNull(); expect(screen.queryByText(/Choose which/)).toBeNull();
    api.mockRejectedValue(new Error("Configuration unavailable"));
    view.rerender(<MemoryRouter><GrowthEventSettingsCard repositoryId={8} /></MemoryRouter>);
    expect(await screen.findByRole("alert")).toHaveTextContent("Configuration unavailable");
  });
  it("separates zero-query, empty selection and no-history Search states", async () => {
    let queries: typeof searchHistory[] = [];
    api.mockImplementation(async (url: string) => String(url).includes("search-visibility") ? queries : respond(String(url)));
    const view = render(<MemoryRouter initialEntries={["/admin/repositories/13?tab=search"]}><Routes><Route path="/admin/repositories/:id" element={<RepositoryDetailsPage />} /></Routes></MemoryRouter>);
    expect(await screen.findByText("No search queries configured yet.")).toBeInTheDocument();
    expect(screen.getByText("An administrator can configure search queries.")).toBeInTheDocument(); expect(screen.queryByRole("checkbox", { name: "Chart lines" })).toBeNull();
    view.unmount(); document.cookie = "search_visibility_queries_13=; path=/; max-age=0"; queries = [{ ...searchHistory, points: [] }];
    render(<MemoryRouter initialEntries={["/admin/repositories/13?tab=search"]}><Routes><Route path="/admin/repositories/:id" element={<RepositoryDetailsPage />} /></Routes></MemoryRouter>);
    expect(await screen.findByText("No rank history for the selected queries yet.")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("checkbox", { name: `Chart line for ${searchHistory.query.name}` }));
    expect(screen.getByText("Select search queries from the table to display rank history")).toBeInTheDocument();
  });
  it("resets forward route scroll only, preserving query interactions, POP and intentional anchors", async () => {
    const scroll = vi.spyOn(window, "scrollTo").mockImplementation(() => {}); const user = userEvent.setup();
    function Controls() { const navigate = useNavigate(); return <><RouteScroll /><Link to="/history">History</Link><Link to="/history?days=7">Period</Link><Link to="/repo?focus=health">Health</Link><Link to="/detail#target">Anchor</Link><button onClick={() => navigate(-1)}>Back</button></>; }
    router(<Controls />); expect(scroll).not.toHaveBeenCalled();
    await user.click(screen.getByText("History")); expect(scroll).toHaveBeenCalledExactlyOnceWith(0, 0);
    await user.click(screen.getByText("Period")); expect(scroll).toHaveBeenCalledTimes(1);
    await user.click(screen.getByText("Health")); await user.click(screen.getByText("Anchor")); await user.click(screen.getByText("Back")); expect(scroll).toHaveBeenCalledTimes(1);
    scroll.mockRestore();
  });
  it("selects Topics competitors from real histories, preserves sorting and resets on scope changes", async () => {
    const user = userEvent.setup();
    render(<MemoryRouter initialEntries={["/admin/repositories/13/topics/outbox"]}><Routes><Route path="/admin/repositories/:repositoryId/topics/:topic" element={<TopicDetailsPage />} /></Routes></MemoryRouter>);
    await screen.findAllByRole("article");
    const check = screen.getByRole("checkbox", { name: "Show big/outbox on the chart" });
    await user.click(check); expect(check).toBeChecked();
    expect(screen.queryByRole("tablist", { name: "Chart series" })).toBeNull();
    expect(screen.getByRole("group", { name: "13:topic:7:all series" })).toHaveTextContent("big/outbox");
    expect(screen.getByRole("group", { name: "13:topic:7:all series" })).toHaveTextContent(repository.fullName);
    await user.selectOptions(screen.getByRole("combobox", { name: "Sort by" }), "stars"); expect(check).toBeChecked();
    expect(screen.getByRole("link", { name: "big/outbox" })).toHaveAttribute("href", "/KHolodilin/spring-transactional-outbox-kafka/topics/outbox/competitors/2");
    await user.click(check); expect(check).not.toBeChecked();
    await user.click(check); await user.click(screen.getByRole("button", { name: "Java" }));
    expect(await screen.findByRole("checkbox", { name: "Show big/outbox on the chart" })).not.toBeChecked();
    expect(screen.getByRole("link", { name: "big/outbox" })).toHaveAttribute("href", "/KHolodilin/spring-transactional-outbox-kafka/topics/outbox/competitors/2?scope=language");
  });
  it("ignores delayed Topics replies after changing scope and explains missing competitor history", async () => {
    const user = userEvent.setup();
    let finishOld: (value: unknown) => void = () => {};
    let defer = true;
    api.mockImplementation(async (url: string) => {
      if (url.includes("/series?scope=all") && defer) return new Promise(resolve => { finishOld = resolve; });
      if (url.includes("/series?scope=language")) return [];
      return respond(String(url));
    });
    render(<MemoryRouter initialEntries={["/admin/repositories/13/topics/outbox"]}><Routes><Route path="/admin/repositories/:repositoryId/topics/:topic" element={<TopicDetailsPage />} /></Routes></MemoryRouter>);
    await screen.findByRole("button", { name: "Java" });
    await user.click(screen.getByRole("button", { name: "Java" }));
    await screen.findAllByRole("article");
    const check = screen.getByRole("checkbox", { name: "Show big/outbox on the chart" });
    await user.click(check);
    expect(screen.getByRole("status")).toHaveTextContent("No rank history for one or more selected competitors.");
    await act(async () => finishOld({ invalid: "old scope must never reach chart" }));
    expect(check).toBeChecked(); expect(screen.getByRole("status")).toBeInTheDocument();
    defer = false;
    await user.click(screen.getByRole("button", { name: "All" }));
    expect(await screen.findByRole("checkbox", { name: "Show big/outbox on the chart" })).not.toBeChecked();
  });
  it("keeps Topics usable when optional histories fail, with a separate missing-series state", async () => {
    api.mockImplementation(async (url: string) => {
      if (url.includes("/series")) throw new Error("Series unavailable");
      return respond(String(url));
    });
    const view = render(<MemoryRouter initialEntries={["/admin/repositories/13/topics/outbox"]}><Routes><Route path="/admin/repositories/:repositoryId/topics/:topic" element={<TopicDetailsPage />} /></Routes></MemoryRouter>);
    const user = userEvent.setup();
    await screen.findAllByRole("article");
    const checkbox = screen.getByRole("checkbox", { name: "Show big/outbox on the chart" });
    await user.click(checkbox);
    expect(checkbox).toBeChecked();
    expect(await screen.findByRole("status")).toHaveTextContent("No rank history");
    view.unmount();
    api.mockImplementation(async (url: string) => { if (url.includes("/results")) throw new Error("No snapshot"); return respond(String(url)); });
    render(<MemoryRouter initialEntries={["/admin/repositories/13/topics/outbox"]}><Routes><Route path="/admin/repositories/:repositoryId/topics/:topic" element={<TopicDetailsPage />} /></Routes></MemoryRouter>);
    expect(await screen.findByText("No topic snapshot yet.")).toBeInTheDocument();
  });
  it("handles a delayed initial Topics history after unmount and an initial load error", async () => {
    let finish: (value: unknown) => void = () => {};
    api.mockImplementation(async (url: string) => url.includes("/history") ? new Promise(resolve => { finish = resolve; }) : respond(String(url)));
    const view = render(<MemoryRouter initialEntries={["/admin/repositories/13/topics/outbox"]}><Routes><Route path="/admin/repositories/:repositoryId/topics/:topic" element={<TopicDetailsPage />} /></Routes></MemoryRouter>);
    view.unmount(); await act(async () => finish(topicHistory));
    api.mockRejectedValue(new Error("Topic unavailable"));
    render(<MemoryRouter initialEntries={["/admin/repositories/13/topics/outbox"]}><Routes><Route path="/admin/repositories/:repositoryId/topics/:topic" element={<TopicDetailsPage />} /></Routes></MemoryRouter>);
    expect(await screen.findByText("Topic unavailable")).toBeInTheDocument();
  });
  it("keeps admin setup actionable for a repository with no queries", async () => {
    auth.me = { ...anonymousMe, authenticated: true, actions: ["MUTATE"] };
    render(<MemoryRouter initialEntries={["/admin/repositories/13?tab=search"]}><Routes><Route path="/admin/repositories/:id" element={<RepositoryDetailsPage />} /></Routes></MemoryRouter>);
    expect(await screen.findByText("Add a search query to start tracking visibility.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Add query" })).toBeInTheDocument();
    expect(screen.queryByRole("checkbox", { name: "Chart lines" })).toBeNull();
  });

  it("does not show a late Topics error after navigating away", async () => {
    let fail: (error: Error) => void = () => {};
    api.mockImplementation(async (url: string) => url.includes("/history") ? new Promise((_, reject) => { fail = reject; }) : respond(String(url)));
    const view = render(<MemoryRouter initialEntries={["/admin/repositories/13/topics/outbox"]}><Routes><Route path="/admin/repositories/:repositoryId/topics/:topic" element={<TopicDetailsPage />} /></Routes></MemoryRouter>);
    view.unmount(); await act(async () => fail(new Error("Late error")));
    expect(screen.queryByText("Late error")).toBeNull();
  });
  it("explains unavailable Search competitor history without fabricating ranks", async () => {
    api.mockImplementation(async (url: string) => {
      if (url.includes("/search-queries/") && url.endsWith("/history")) return searchHistory;
      if (url.includes("/search-queries/") && url.endsWith("/results")) return searchResults;
      if (url.endsWith("/series")) return { series: [{ githubRepositoryId: 99, fullName: repository.fullName, points: [] }, { githubRepositoryId: 1, fullName: "other/repo", points: [] }] };
      return respond(String(url));
    });
    render(<MemoryRouter initialEntries={["/admin/repositories/13/search-queries/1"]}><Routes><Route path="/admin/repositories/:repositoryId/search-queries/:queryId" element={<QueryDetailsPage />} /></Routes></MemoryRouter>);
    await screen.findAllByRole("article");
    await userEvent.click(screen.getByRole("checkbox", { name: "Show other/repo on the chart" }));
    expect(await screen.findByRole("status")).toHaveTextContent("No history for one or more selected competitors.");
  });

  it("does not initialize Paths selection from stale Referrers while history is loading", async () => {
    const user = userEvent.setup();
    let finishPaths: (value: unknown) => void = () => {};
    api.mockImplementation(async (url: string) => {
      if (url.includes("traffic-history") && url.includes("kind=paths")) return new Promise(resolve => { finishPaths = resolve; });
      if (url.includes("traffic-history")) return snapshotHistory;
      return respond(String(url));
    });
    render(<MemoryRouter initialEntries={["/admin/repositories/13/traffic/history?kind=referrers"]}><Routes><Route path="/admin/repositories/:id/traffic/history" element={<TrafficHistoryPage />} /></Routes></MemoryRouter>);
    await screen.findAllByRole("article");
    expect(screen.getByRole("checkbox", { name: "Show github.com on the chart" })).toBeChecked();
    await user.click(screen.getByRole("button", { name: "Popular Paths" }));
    expect(screen.queryByRole("article")).toBeNull();
    const paths = { ...snapshotHistory, kind: "PATHS", rows: [{ ...snapshotHistory.rows[0], key: "/repository/very/long/path", title: "Path" }] };
    await act(async () => finishPaths(paths));
    expect(await screen.findByRole("checkbox", { name: "Show /repository/very/long/path on the chart" })).toBeChecked();
    expect(screen.queryByRole("checkbox", { name: "Show github.com on the chart" })).toBeNull();
    await user.click(screen.getByRole("button", { name: "Referrers" }));
    expect(await screen.findByRole("checkbox", { name: "Show github.com on the chart" })).toBeChecked();
  });

});
