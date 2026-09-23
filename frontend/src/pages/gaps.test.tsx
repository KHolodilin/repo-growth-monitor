import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { dashboardReady, repository, searchResults, snapshotHistory, topicResults, traffic } from "../test/fixtures";
import { GrowthEventSettingsCard } from "../components/GrowthEventSettingsCard";
import { PersistentECharts } from "../components/PersistentECharts";
import { SearchResultsTable } from "../components/SearchResultsTable";
import { SnapshotHistoryChart } from "../components/SnapshotHistoryChart";
import { TopicResultsTable } from "../components/TopicResultsTable";
import { DashboardPage } from "./DashboardPage";
import { QueryDetailsPage } from "./QueryDetailsPage";
import { SearchResultsPage } from "./SearchResultsPage";
import { TopicDetailsPage } from "./TopicDetailsPage";
import { TrafficHistoryPage } from "./TrafficHistoryPage";
import { RepositoryDetailsPage } from "./RepositoryDetailsPage";

const { api } = vi.hoisted(() => ({ api: vi.fn() }));
vi.mock("../lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../lib/api")>();
  return { ...actual, api };
});

describe("coverage gaps", () => {
  beforeEach(() => {
    api.mockReset();
  });

  it("covers dashboard first collection, all-period KPIs and failed collect", async () => {
    const user = userEvent.setup();
    api.mockImplementation(async (path: string) => {
      if (String(path).includes("/collect")) {
        throw new Error("busy");
      }
      return {
        ...dashboardReady,
        lastSyncAt: undefined,
        state: "FIRST_COLLECTION",
        activeCollection: { status: "RUNNING", successfulJobs: 0, plannedJobs: 1 },
        summary: {
          ...dashboardReady.summary,
          stars: { total: 21, change: -1 },
          views: { value: 10, growthPercent: 0 },
        },
        repositories: [
          { ...dashboardReady.repositories[0], growthPercent: null, collectionStatus: "SUCCESS" },
          { ...dashboardReady.repositories[0], id: 8, fullName: "KHolodilin/other", visitors: 1, growthPercent: null },
        ],
      };
    });
    render(
      <MemoryRouter>
        <Routes>
          <Route path="/" element={<DashboardPage />} />
          <Route path="/repositories/:id" element={<p>opened</p>} />
        </Routes>
      </MemoryRouter>,
    );
    expect(await screen.findByText("No traffic yet")).toBeInTheDocument();
    expect(screen.getByText("Last sync: —")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "all" }));
    await user.click(await screen.findByRole("button", { name: /Collect now/ }));
    expect(await screen.findByText("busy")).toBeInTheDocument();
  });

  it("sorts dashboard columns and opens a repository", async () => {
    const user = userEvent.setup();
    api.mockResolvedValue({
      ...dashboardReady,
      summary: { ...dashboardReady.summary, stars: { total: 21, change: 0 } },
      repositories: [
        dashboardReady.repositories[0],
        { ...dashboardReady.repositories[0], id: 8, fullName: "KHolodilin/other", visitors: 1, growthPercent: null },
      ],
    });
    render(
      <MemoryRouter>
        <Routes>
          <Route path="/" element={<DashboardPage />} />
          <Route path="/repositories/:id" element={<p>opened</p>} />
        </Routes>
      </MemoryRouter>,
    );
    expect(await screen.findByText("KHolodilin/other")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: /Visitors/ }));
    await user.click(screen.getByRole("button", { name: /Views/ }));
    await user.click(screen.getByRole("button", { name: /Clones/ }));
    await user.click(screen.getByRole("button", { name: /Stars/ }));
    await user.click(screen.getByText("KHolodilin/other"));
    expect(await screen.findByText("opened")).toBeInTheDocument();
  });

  it("sorts every results column and opens snapshot source picker", async () => {
    const user = userEvent.setup();
    render(
      <MemoryRouter>
        <SearchResultsTable
          rows={[
            searchResults.rows[0],
            {
              ...searchResults.rows[1],
              positionDelta: null,
              result: { ...searchResults.rows[1].result, activityAt: null, metadataUpdatedAt: "2026-09-23T06:00:00Z" },
            },
          ]}
          repositoryId={13}
          trackedPosition={4}
        />
        <TopicResultsTable
          rows={[
            topicResults.rows[0],
            { ...topicResults.rows[1], result: { ...topicResults.rows[1].result, language: undefined, htmlUrl: undefined } },
          ]}
          repositoryId={13}
        />
        <SnapshotHistoryChart title="Referrer Traffic (by day)" kind="referrers" history={snapshotHistory} />
        <SnapshotHistoryChart
          title="Path Traffic (by day)"
          kind="paths"
          history={{ ...snapshotHistory, kind: "PATHS", dates: ["2026-09-23"], rows: [] }}
        />
        <PersistentECharts chartId="plain" series={[{ key: "Views", name: "Views" }]} option={{}} />
        <PersistentECharts chartId="list" series={[{ key: "Views", name: "Views" }]} option={{ legend: [] }} />
      </MemoryRouter>,
    );
    for (const name of ["#", "Watchers", "Forks", "Contributors", "Activity", "Δ", "Stars", "Language"]) {
      const buttons = screen.queryAllByRole("button", { name: new RegExp(`^${name}`) });
      if (buttons[0]) {
        await user.click(buttons[0]);
        await user.click(buttons[0]);
      }
    }
    await user.click(screen.getByRole("button", { name: /Select sources|more sources/ }));
    expect(screen.getAllByText("github.com").length).toBeGreaterThan(0);
    await user.click(screen.getAllByRole("checkbox")[0]);
    await user.click(screen.getAllByRole("button", { name: "Views" })[0]);
    expect(screen.getByText("Not enough history yet.")).toBeInTheDocument();
  });

  it("covers settings errors and empty/error pages", async () => {
    const user = userEvent.setup();
    api.mockRejectedValueOnce(new Error("settings down"));
    const loadError = render(<GrowthEventSettingsCard repositoryId={13} />);
    expect(await screen.findByText("settings down")).toBeInTheDocument();
    loadError.unmount();

    api.mockResolvedValueOnce([{ repositoryId: 13, eventType: "STAR_MILESTONE", enabled: true }]);
    const { unmount } = render(<GrowthEventSettingsCard repositoryId={8} />);
    expect(await screen.findByText("Star milestones")).toBeInTheDocument();
    api.mockRejectedValueOnce(new Error("save failed"));
    await user.click(screen.getByLabelText("Star milestones"));
    expect(await screen.findByText("save failed")).toBeInTheDocument();
    unmount();

    api.mockRejectedValue(new Error("gone"));
    const missing = render(
      <MemoryRouter initialEntries={["/search-runs/1"]}>
        <Routes>
          <Route path="/search-runs/:id" element={<SearchResultsPage />} />
        </Routes>
      </MemoryRouter>,
    );
    expect(await screen.findByText("gone")).toBeInTheDocument();
    missing.unmount();

    const queryError = render(
      <MemoryRouter initialEntries={["/repositories/13/search-queries/5"]}>
        <Routes>
          <Route path="/repositories/:repositoryId/search-queries/:queryId" element={<QueryDetailsPage />} />
        </Routes>
      </MemoryRouter>,
    );
    expect(await screen.findByText("gone")).toBeInTheDocument();
    queryError.unmount();

    const topicError = render(
      <MemoryRouter initialEntries={["/repositories/13/topics/outbox"]}>
        <Routes>
          <Route path="/repositories/:repositoryId/topics/:topic" element={<TopicDetailsPage />} />
        </Routes>
      </MemoryRouter>,
    );
    expect(await screen.findByText("gone")).toBeInTheDocument();
    topicError.unmount();

    const trafficError = render(
      <MemoryRouter initialEntries={["/repositories/13/traffic/history"]}>
        <Routes>
          <Route path="/repositories/:id/traffic/history" element={<TrafficHistoryPage />} />
        </Routes>
      </MemoryRouter>,
    );
    expect(await screen.findByText("gone")).toBeInTheDocument();
    trafficError.unmount();
  });

  it("covers repository details without collection and period change", async () => {
    const user = userEvent.setup();
    api.mockImplementation(async (path: string) => {
      const url = String(path);
      if (url.endsWith("/repositories/13")) {
        return { ...repository, language: "Java", topics: [] };
      }
      if (url.includes("/traffic?")) {
        return { ...traffic, lastCollection: undefined, pathSnapshotAt: undefined, referrerSnapshotAt: undefined };
      }
      if (url.includes("visibility") || url.includes("growth-events") || url.includes("stats-history")) {
        return url.includes("stats-history") ? { repositoryId: 13, period: "1d", points: [] } : [];
      }
      if (url.includes("growth-event-settings")) {
        return [{ repositoryId: 13, eventType: "STAR_MILESTONE", enabled: true }];
      }
      return [];
    });
    render(
      <MemoryRouter initialEntries={["/repositories/13?tab=settings"]}>
        <Routes>
          <Route path="/repositories/:id" element={<RepositoryDetailsPage />} />
        </Routes>
      </MemoryRouter>,
    );
    expect(await screen.findByText("Automatic events")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "1d" }));
    await user.click(screen.getByRole("button", { name: "Overview" }));
    expect(await screen.findByText("No collection data yet.")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Topics Visibility" }));
    expect(await screen.findByText(/No topics yet/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Java" }));
    expect(await screen.findByText(/No language-scoped topic watches yet/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Stats" }));
    expect(await screen.findByText("No stats for this period.")).toBeInTheDocument();
  });
});
