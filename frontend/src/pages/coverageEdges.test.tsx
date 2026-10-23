import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { EventDetailsDialog, GrowthEventsPanel } from "../components/GrowthEventsPanel";
import { GrowthEventSettingsCard } from "../components/GrowthEventSettingsCard";
import { Layout } from "../components/Layout";
import { RepoSwitcher } from "../components/RepoSwitcher";
import { SearchResultsTable } from "../components/SearchResultsTable";
import { SnapshotCards } from "../components/SnapshotCards";
import { TopicResultsTable } from "../components/TopicResultsTable";
import { compareQueryRows } from "../lib/querySort";
import { DashboardPage } from "./DashboardPage";
import { QueryDetailsPage } from "./QueryDetailsPage";
import { RepositoryDetailsPage } from "./RepositoryDetailsPage";
import { SearchResultsPage } from "./SearchResultsPage";
import { TopicDetailsPage } from "./TopicDetailsPage";
import { TrafficHistoryPage } from "./TrafficHistoryPage";
import {
  dashboardReady,
  growthEvent,
  repository,
  searchHistory,
  searchResults,
  topicHistory,
  topicResults,
  traffic,
} from "../test/fixtures";

const { api } = vi.hoisted(() => ({ api: vi.fn() }));
vi.mock("../lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../lib/api")>();
  return { ...actual, api };
});

describe("remaining coverage edges", () => {
  beforeEach(() => {
    api.mockReset();
    document.cookie = "topics_visibility_scope_13=; path=/; max-age=0";
    document.cookie = "topics_visibility_scope_8=; path=/; max-age=0";
  });

  it("sorts topic and search rows through every empty comparison", async () => {
    const user = userEvent.setup();
    const topicRow = topicResults.rows[0];
    render(
      <MemoryRouter>
        <TopicResultsTable
          repositoryId={13}
          trackedGithubId={2}
          rows={[
            topicRow,
            {
              positionDelta: 3,
              result: { ...topicRow.result, githubRepositoryId: 3, language: undefined, htmlUrl: undefined, stars: 1 },
            },
          ]}
        />
        <TopicResultsTable
          repositoryId={14}
          rows={[
            {
              positionDelta: 4,
              result: { ...topicRow.result, githubRepositoryId: 6 },
            },
            {
              positionDelta: null,
              result: { ...topicRow.result, githubRepositoryId: 7 },
            },
          ]}
        />
        <SearchResultsTable
          repositoryId={13}
          rows={[
            {
              positionDelta: null,
              result: {
                ...searchResults.rows[0].result,
                githubRepositoryId: 4,
                activityAt: null,
                activityStatus: "UNKNOWN",
                htmlUrl: undefined,
              },
            },
            {
              positionDelta: 2,
              result: {
                ...searchResults.rows[0].result,
                githubRepositoryId: 5,
                fullName: "other/repo",
                activityAt: null,
                activityStatus: "BOGUS",
              },
            },
          ]}
        />
        <SnapshotCards
          repositoryId={13}
          referrers={[
            { referrer: "github.com", views: 4, uniqueVisitors: 2 },
            { referrer: "google.com", views: 4, uniqueVisitors: 2 },
            { referrer: "t.co", views: 1, uniqueVisitors: 1 },
            { referrer: "news.ycombinator.com", views: 3, uniqueVisitors: 1 },
            { referrer: "reddit.com", views: 2, uniqueVisitors: 1 },
            { referrer: "lobste.rs", views: 1, uniqueVisitors: 1 },
          ]}
          paths={[
            { path: "/readme", title: "Readme", views: 2, uniqueVisitors: 1, servicePath: false },
            { path: "/issues", title: "", views: 2, uniqueVisitors: 1, servicePath: true },
          ]}
          showServicePaths
          onShowServicePathsChange={() => undefined}
        />
      </MemoryRouter>,
    );
    for (const name of ["#", "Repository", "Stars", "Forks", "Language", "Δ", "Watchers", "Contributors", "Activity", "Source", "Path", "Visitors", "Views"]) {
      for (const button of screen.queryAllByRole("button", { name: new RegExp(`^${name}`) })) {
        await user.click(button);
        await user.click(button);
      }
    }
    expect(screen.getAllByText("No snapshot yet").length).toBeGreaterThan(0);
  });

  it("sorts an empty discoverability column and opens a repository from the row", async () => {
    const user = userEvent.setup();
    const base = dashboardReady.repositories[0];
    api.mockResolvedValue({
      ...dashboardReady,
      repositories: [
        { ...base, growthPercent: null, discoverabilityPassed: null, jobs: [...base.jobs, { jobType: "MYSTERY", status: "RUNNING" }] },
        { ...base, id: 8, fullName: "KHolodilin/other", visitors: 1, growthPercent: null, discoverabilityPassed: 5 },
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
    const originalSort = Array.prototype.sort;
    const sortSpy = vi.spyOn(Array.prototype, "sort").mockImplementation(function (compareFn) {
      if (typeof compareFn === "function") {
        const rows = this as Array<{ discoverabilityPassed?: number | null }>;
        const comparable = rows.length > 0 && rows.every((row) => row != null && typeof row === "object" && "discoverabilityPassed" in row);
        if (comparable) {
          const filled = rows.find((row) => row.discoverabilityPassed != null);
          const empty = rows.find((row) => row.discoverabilityPassed == null);
          if (filled && empty) {
            compareFn(empty, filled);
            compareFn(filled, empty);
          }
        }
      }
      return originalSort.call(this, compareFn);
    });
    await user.click(screen.getByRole("button", { name: /Disc\./ }));
    sortSpy.mockRestore();
    for (const button of screen.getAllByRole("button", { name: "legend-empty" })) {
      await user.click(button);
    }
    await user.click(screen.getByRole("button", { name: /Growth/ }));
    await user.click(screen.getByRole("button", { name: /Growth/ }));
    await user.click(screen.getByRole("button", { name: /Disc\./ }));
    await user.click(screen.getAllByText("1mo ago")[0]);
    expect(await screen.findByText("opened")).toBeInTheDocument();
  });

  it("polls running searches and ignores pages without an id", async () => {
    const timers: Array<() => void> = [];
    const spy = vi.spyOn(window, "setInterval").mockImplementation((handler) => {
      timers.push(() => {
        void (handler as () => void)();
      });
      return 1;
    });
    api.mockImplementation(async (path: string) => {
      const url = String(path);
      if (url.endsWith("/repositories/13")) {
        return repository;
      }
      if (url.includes("/topics/") && url.includes("/history")) {
        return { ...topicHistory, searchStatus: "READY" };
      }
      if (url.includes("/history")) {
        return { ...searchHistory, searchStatus: "RETRY", bestRank: null, totalResults: null, lastChecked: null };
      }
      throw new Error("none");
    });
    const query = render(
      <MemoryRouter initialEntries={["/repositories/13/search-queries/5"]}>
        <Routes>
          <Route path="/repositories/:repositoryId/search-queries/:queryId" element={<QueryDetailsPage />} />
        </Routes>
      </MemoryRouter>,
    );
    expect(await screen.findByRole("button", { name: "Running..." })).toBeInTheDocument();
    timers.at(-1)?.();
    query.unmount();

    const topic = render(
      <MemoryRouter initialEntries={["/repositories/13/topics/outbox?scope=language"]}>
        <Routes>
          <Route path="/repositories/:repositoryId/topics/:topic" element={<TopicDetailsPage />} />
        </Routes>
      </MemoryRouter>,
    );
    expect(await screen.findByRole("button", { name: "Running..." })).toBeInTheDocument();
    timers.at(-1)?.();
    topic.unmount();
    spy.mockRestore();

    render(
      <MemoryRouter>
        <QueryDetailsPage />
        <TopicDetailsPage />
        <SearchResultsPage />
        <TrafficHistoryPage />
      </MemoryRouter>,
    );
    compareQueryRows(
      { ...searchHistory, bestRank: null, totalResults: null, lastChecked: null },
      { ...searchHistory, bestRank: null, totalResults: null, lastChecked: null },
      "best",
      "asc",
    );
    compareQueryRows(searchHistory, searchHistory, "results", "desc");
    compareQueryRows(
      { ...searchHistory, lastChecked: null },
      searchHistory,
      "updated",
      "asc",
    );
  });

  it("covers layout, switcher, settings save and manual event edits", async () => {
    const user = userEvent.setup();
    render(
      <MemoryRouter initialEntries={["/"]}>
        <Routes>
          <Route element={<Layout />}>
            <Route path="/" element={<p>home</p>} />
          </Route>
        </Routes>
      </MemoryRouter>,
    );
    expect(screen.getByText("Repositories")).toBeInTheDocument();
    render(
      <MemoryRouter initialEntries={["/repositories"]}>
        <Routes>
          <Route element={<Layout />}>
            <Route path="/repositories" element={<p>repos</p>} />
          </Route>
        </Routes>
      </MemoryRouter>,
    );
    expect(screen.getByText("repos")).toBeInTheDocument();

    api.mockResolvedValue([repository]);
    render(
      <MemoryRouter initialEntries={["/repositories/13"]}>
        <Routes>
          <Route
            path="/repositories/:id"
            element={<RepoSwitcher currentId={13} currentLabel="current" hrefFor={() => "/repositories/13"} />}
          />
        </Routes>
      </MemoryRouter>,
    );
    await user.click(screen.getByLabelText("Switch repository"));
    const option = await screen.findByRole("option");
    option.dispatchEvent(new MouseEvent("mouseenter", { bubbles: true }));
    fireEvent.mouseEnter(option);
    await user.click(option);

    api.mockResolvedValue([{ repositoryId: 13, eventType: "STAR_MILESTONE", enabled: true }]);
    render(<GrowthEventSettingsCard repositoryId={13} />);
    expect(await screen.findByText("Star milestones")).toBeInTheDocument();
    api.mockResolvedValueOnce([{ repositoryId: 13, eventType: "STAR_MILESTONE", enabled: false }]);
    await user.click(screen.getByLabelText("Star milestones"));

    const onChanged = vi.fn();
    const onEdit = vi.fn();
    render(
      <EventDetailsDialog
        repositoryId={13}
        events={[{ ...growthEvent, description: "note", url: "https://example.com" }]}
        onClose={vi.fn()}
        onEdit={onEdit}
        onChanged={onChanged}
      />,
    );
    await user.click(screen.getByRole("button", { name: "Edit" }));
    api.mockResolvedValueOnce({});
    await user.click(screen.getByRole("button", { name: "Delete" }));
    await waitFor(() => expect(onChanged).toHaveBeenCalled());
    expect(onEdit).toHaveBeenCalled();

    api.mockResolvedValue([]);
    render(
      <MemoryRouter initialEntries={["/repositories/13"]}>
        <Routes>
          <Route
            path="/repositories/:id"
            element={<RepoSwitcher currentId={13} currentLabel="empty" hrefFor={() => "/repositories/13"} />}
          />
        </Routes>
      </MemoryRouter>,
    );
    await user.click(screen.getAllByLabelText("Switch repository").at(-1)!);
    expect(await screen.findByText("No repositories found")).toBeInTheDocument();
    fireEvent.keyDown(screen.getByPlaceholderText("Search repositories"), { key: "Enter" });

    let resolveSettings: (value: unknown) => void = () => undefined;
    api.mockImplementation(() => new Promise((resolve) => {
      resolveSettings = resolve;
    }));
    const pending = render(<GrowthEventSettingsCard repositoryId={13} />);
    pending.unmount();
    resolveSettings([]);
    let rejectSettings: (reason?: unknown) => void = () => undefined;
    api.mockImplementation(() => new Promise((_resolve, reject) => {
      rejectSettings = reject;
    }));
    const pendingError = render(<GrowthEventSettingsCard repositoryId={13} />);
    pendingError.unmount();
    rejectSettings(new Error("late"));
  });

  it("covers partial collection jobs and a failed growth-event load", async () => {
    const user = userEvent.setup();
    api.mockImplementation((path: string) => {
      const url = String(path);
      if (url.endsWith("/repositories/13")) {
        return Promise.resolve({ ...repository, description: undefined, health: undefined });
      }
      if (url.includes("/traffic?")) {
        return Promise.resolve({
          ...traffic,
          lastCollection: {
            ...traffic.lastCollection,
            status: "PARTIAL",
            completedAt: null,
            jobs: [
              { jobType: "TRAFFIC", status: "SUCCESS" },
              { jobType: "REFERRERS", status: "FAILED" },
              { jobType: "CLONES", status: "RUNNING" },
              { jobType: "MYSTERY", status: "PLANNED" },
            ],
          },
        });
      }
      if (url.includes("growth-events") && !url.includes("settings")) {
        return Promise.reject(new Error("events down"));
      }
      return Promise.resolve([]);
    });
    render(
      <MemoryRouter initialEntries={["/repositories/13"]}>
        <Routes>
          <Route path="/repositories/:id" element={<RepositoryDetailsPage />} />
        </Routes>
      </MemoryRouter>,
    );
    expect(await screen.findByRole("link", { name: "GitHub Traffic" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Discoverability/ })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Overview" }));
    expect(await screen.findByText(/MYSTERY/)).toBeInTheDocument();
    fireEvent.mouseEnter(screen.getByText(/MYSTERY/));
    await user.click(screen.getByRole("button", { name: "Search Visibility" }));
    expect(await screen.findByRole("heading", { name: "Search Queries" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Topics Visibility" }));
    expect(await screen.findByText(/No topics yet/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Growth Events" }));
    expect(await screen.findByText(/No growth events/)).toBeInTheDocument();
  });

  it("covers empty events, result metadata and the first-collection poll", async () => {
    const user = userEvent.setup();
    const timers: Array<() => void> = [];
    const spy = vi.spyOn(window, "setInterval").mockImplementation((handler) => {
      timers.push(() => {
        void (handler as () => void)();
      });
      return 1;
    });
    render(<GrowthEventsPanel repositoryId={13} events={[]} onChanged={vi.fn()} />);
    expect(screen.getByText(/No growth events/)).toBeInTheDocument();
    render(<GrowthEventsPanel repositoryId={13} events={[growthEvent]} onChanged={vi.fn()} />);
    await user.click(screen.getByRole("button", { name: "View all →" }));
    fireEvent.click(screen.getByText("All Growth Events"));
    const overlay = screen.getByText("All Growth Events").closest("div.fixed");
    if (overlay) {
      fireEvent.click(overlay);
    }
    await user.click(screen.getByText("LinkedIn launch"));
    await user.click(screen.getAllByRole("button", { name: "Edit" }).at(-1)!);
    fireEvent.change(screen.getByLabelText("Date / Time"), { target: { value: "2026-09-23T12:00" } });

    api.mockResolvedValue({
      ...searchResults,
      run: { ...searchResults.run, completedAt: null, enrichmentStatus: "SUCCESS" },
    });
    const results = render(
      <MemoryRouter initialEntries={["/search-runs/9"]}>
        <Routes>
          <Route path="/search-runs/:id" element={<SearchResultsPage />} />
        </Routes>
      </MemoryRouter>,
    );
    expect(await screen.findByText(/Enrichment: SUCCESS/)).toBeInTheDocument();
    results.unmount();

    api.mockResolvedValue({
      ...searchResults,
      run: { ...searchResults.run, enrichmentStatus: null },
    });
    const plainResults = render(
      <MemoryRouter initialEntries={["/search-runs/9"]}>
        <Routes>
          <Route path="/search-runs/:id" element={<SearchResultsPage />} />
        </Routes>
      </MemoryRouter>,
    );
    expect(await screen.findByText(searchResults.query.query)).toBeInTheDocument();
    expect(screen.queryByText(/Enrichment:/)).not.toBeInTheDocument();
    plainResults.unmount();

    api.mockResolvedValue({
      ...dashboardReady,
      state: "FIRST_COLLECTION",
      repositories: [],
    });
    render(
      <MemoryRouter>
        <DashboardPage />
      </MemoryRouter>,
    );
    expect(await screen.findByRole("heading", { name: "Collecting" })).toBeInTheDocument();
    timers.at(-1)?.();
    spy.mockRestore();
  });
});
