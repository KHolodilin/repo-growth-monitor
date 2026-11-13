import * as chartExports from "../lib/chartExport";
import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { RepositoryDetailsPage } from "./RepositoryDetailsPage";
import { QueryDetailsPage } from "./QueryDetailsPage";
import { TopicDetailsPage } from "./TopicDetailsPage";
import { SnapshotHistoryView } from "../components/SnapshotHistoryView";
import { repository, searchHistory, searchResults, snapshotHistory, topicHistory, topicResults, traffic } from "../test/fixtures";
const { api } = vi.hoisted(() => ({ api: vi.fn() }));
vi.mock("../lib/api", async original => ({ ...await original<typeof import("../lib/api")>(), api }));
beforeEach(() => {
  api.mockReset();
  vi.stubGlobal("matchMedia", vi.fn(() => ({ matches: true, addEventListener: vi.fn(), removeEventListener: vi.fn() })));
});
afterEach(() => vi.unstubAllGlobals());
function mount(path: string, page: React.ReactNode) {
  return render(<MemoryRouter initialEntries={[path]}><Routes><Route path="/admin/repositories/:id" element={page} /><Route path="/admin/repositories/:repositoryId/search-queries/:queryId" element={page} /><Route path="/admin/repositories/:repositoryId/topics/:topic" element={page} /></Routes></MemoryRouter>);
}
describe("chart batch integration", () => {
  it("retains the new total-Clones mobile choice through period changes and ignores a delayed stale response", async () => {
    const user = userEvent.setup(); let finishOld: (value: unknown) => void = () => {};
    api.mockImplementation(async (url: string) => {
      if (url.endsWith("/admin/repositories/13")) return repository;
      if (url.includes("/traffic?")) {
        if (url.includes("period=30d")) return new Promise(resolve => { finishOld = resolve; });
        if (url.includes("period=1d")) return { ...traffic, traffic: [{ date: "2026-11-02", views: null, uniqueVisitors: null, clones: 8, uniqueCloners: null }] };
        return traffic;
      }
      return [];
    });
    mount("/admin/repositories/13?period=7d", <RepositoryDetailsPage />);
    // The app restores its period preference, so explicitly supply a short initial cookie.
    await screen.findByRole("tab", { name: "Clones" });
    await user.click(screen.getByRole("tab", { name: "Clones" }));
    await user.click(screen.getByRole("button", { name: "30d" }));
    await user.click(screen.getByRole("button", { name: "1d" }));
    expect(await screen.findByRole("tab", { name: "Clones", selected: true })).toBeInTheDocument();
    await act(async () => finishOld({ ...traffic, traffic: [] }));
    expect(screen.getByRole("tab", { name: "Clones", selected: true })).toBeInTheDocument();
    expect(screen.queryByText("No traffic data for selected period")).toBeNull();
    await user.click(screen.getByRole("tab", { name: "Unique Cloners" }));
    expect(screen.getByRole("tab", { name: "Unique Cloners", selected: true })).toBeInTheDocument();
  });
  it("uses the compact mobile summary in both detail pages and updates it when Topic scope changes", async () => {
    const user = userEvent.setup();
    api.mockImplementation(async (url: string) => {
      if (url.endsWith("/admin/repositories/13")) return repository;
      if (url.includes("/history")) return url.includes("/topics/") ? { ...topicHistory, currentRank: url.includes("scope=language") ? 44 : 24 } : searchHistory;
      if (url.includes("/results")) return url.includes("/topics/") ? topicResults : searchResults;
      return [];
    });
    const query = mount("/admin/repositories/13/search-queries/1", <QueryDetailsPage />);
    expect(await screen.findByLabelText("Rank summary")).toHaveTextContent("Current Rank");
    expect(screen.getAllByText("Total Results")).toHaveLength(1); query.unmount();
    mount("/admin/repositories/13/topics/outbox", <TopicDetailsPage />);
    expect(await screen.findByLabelText("Rank summary")).toHaveTextContent("#24");
    await user.click(screen.getByRole("button", { name: "Java" }));
    expect(await screen.findByLabelText("Rank summary")).toHaveTextContent("#44");
  });
  it("shows a single zero-valued snapshot with simultaneous source selection, and keeps its date independent", async () => {
    const user = userEvent.setup(); const date = "2026-11-02";
    const history = { ...snapshotHistory, dates: [date], rows: ["github.com", "another.example"].map(key => ({ key, cells: [{ date, visitors: 0, views: 5, visitorsDelta: null, viewsDelta: null, firstSeen: true }] })) };
    render(<MemoryRouter><SnapshotHistoryView title="Referrer Snapshot History" kind="referrers" history={history} /></MemoryRouter>);
    expect(await screen.findByRole("combobox", { name: "Snapshot date" })).toHaveValue(date);
    expect(screen.getAllByTestId("echart")).toHaveLength(1);
    expect(screen.queryByRole("tablist", { name: "Chart series" })).toBeNull();
    expect(screen.getByRole("checkbox", { name: "Show github.com on the chart" })).toBeChecked();
    await user.click(screen.getByRole("checkbox", { name: "Show another.example on the chart" }));
    expect(screen.getByRole("checkbox", { name: "Show github.com on the chart" })).toBeChecked();
    expect(screen.getByRole("combobox", { name: "Snapshot date" })).toHaveValue(date);
    expect(screen.queryByText("No data for the selected period.")).toBeNull();
  });
});


describe("query metric labels", () => {
  it("keeps selected competitors while cycling metric headings and restores the metric from its URL", async () => {
    const user = userEvent.setup();
    api.mockImplementation(async (url: string) => {
      if (url.endsWith("/admin/repositories/13")) return repository;
      if (url.includes("/history")) return searchHistory;
      if (url.includes("/results")) return searchResults;
      return [];
    });
    const png = vi.spyOn(chartExports, "downloadChartPng").mockImplementation(() => {});
    const csv = vi.spyOn(chartExports, "downloadChartCsv").mockImplementation(() => {});
    const view = mount("/admin/repositories/13/search-queries/1?metric=stars", <QueryDetailsPage />);
    await waitFor(() => expect(screen.getByRole("heading", { name: "Stars History" })).toBeInTheDocument());
    const rival = screen.getByRole("checkbox", { name: "Show other/repo on the chart" });
    await user.click(rival);
    for (const [metric, title] of [["Forks", "Forks History"], ["Place", "Rank History"], ["Stars", "Stars History"]]) {
      await user.click(screen.getByRole("button", { name: metric }));
      expect(screen.getByRole("heading", { name: title })).toBeInTheDocument();
      expect(rival).toBeChecked();
      await user.click(screen.getByRole("button", { name: "Chart menu" }));
      await user.click(screen.getByRole("menuitem", { name: "PNG" }));
      expect(png).toHaveBeenLastCalledWith(expect.anything(), expect.any(String), title);
      await user.click(screen.getByRole("button", { name: "Chart menu" }));
      await user.click(screen.getByRole("menuitem", { name: "CSV" }));
      expect(csv.mock.lastCall?.[1]).toContain(metric === "Place" ? "(Rank)" : `(${metric})`);
      expect(screen.getByRole("button", { name: metric })).toHaveAttribute("aria-pressed", "true");
    }
    view.unmount();
    mount("/admin/repositories/13/search-queries/1?metric=invalid", <QueryDetailsPage />);
    await waitFor(() => expect(screen.getByRole("heading", { name: "Rank History" })).toBeInTheDocument());
    png.mockRestore(); csv.mockRestore();
  });
});
