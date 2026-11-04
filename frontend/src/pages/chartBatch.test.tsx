import { act, render, screen } from "@testing-library/react";
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
  return render(<MemoryRouter initialEntries={[path]}><Routes><Route path="/repositories/:id" element={page} /><Route path="/repositories/:repositoryId/search-queries/:queryId" element={page} /><Route path="/repositories/:repositoryId/topics/:topic" element={page} /></Routes></MemoryRouter>);
}
describe("chart batch integration", () => {
  it("retains the new total-Clones mobile choice through period changes and ignores a delayed stale response", async () => {
    const user = userEvent.setup(); let finishOld: (value: unknown) => void = () => {};
    api.mockImplementation(async (url: string) => {
      if (url.endsWith("/repositories/13")) return repository;
      if (url.includes("/traffic?")) {
        if (url.includes("period=30d")) return new Promise(resolve => { finishOld = resolve; });
        if (url.includes("period=1d")) return { ...traffic, traffic: [{ date: "2026-11-02", views: null, uniqueVisitors: null, clones: 8, uniqueCloners: null }] };
        return traffic;
      }
      return [];
    });
    mount("/repositories/13?period=7d", <RepositoryDetailsPage />);
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
      if (url.endsWith("/repositories/13")) return repository;
      if (url.includes("/history")) return url.includes("/topics/") ? { ...topicHistory, currentRank: url.includes("scope=language") ? 44 : 24 } : searchHistory;
      if (url.includes("/results")) return url.includes("/topics/") ? topicResults : searchResults;
      return [];
    });
    const query = mount("/repositories/13/search-queries/1", <QueryDetailsPage />);
    expect(await screen.findByLabelText("Rank summary")).toHaveTextContent("Current Rank");
    expect(screen.getAllByText("Total Results")).toHaveLength(1); query.unmount();
    mount("/repositories/13/topics/outbox", <TopicDetailsPage />);
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
