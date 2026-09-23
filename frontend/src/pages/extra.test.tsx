import type { ReactNode } from "react";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { RepoSwitcher } from "../components/RepoSwitcher";
import { GrowthEventsPanel } from "../components/GrowthEventsPanel";
import { RepositoryStatsPanel } from "../components/RepositoryStatsPanel";
import {
  growthEvent,
  repository,
  searchHistory,
  searchResults,
  snapshotHistory,
  topicHistory,
  topicResults,
  traffic,
} from "../test/fixtures";
import { QueryDetailsPage } from "./QueryDetailsPage";
import { RepositoriesPage } from "./RepositoriesPage";
import { RepositoryDetailsPage } from "./RepositoryDetailsPage";
import { SearchResultsPage } from "./SearchResultsPage";
import { TopicDetailsPage } from "./TopicDetailsPage";
import { TrafficHistoryPage } from "./TrafficHistoryPage";

const { api } = vi.hoisted(() => ({ api: vi.fn() }));
vi.mock("../lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../lib/api")>();
  return { ...actual, api };
});

function renderAt(path: string, ui: ReactNode) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/repositories" element={ui} />
        <Route path="/repositories/:id" element={ui} />
        <Route path="/repositories/:id/traffic/history" element={ui} />
        <Route path="/repositories/:repositoryId/search-queries/:queryId" element={ui} />
        <Route path="/repositories/:repositoryId/topics/:topic" element={ui} />
        <Route path="/search-runs/:id" element={ui} />
      </Routes>
    </MemoryRouter>,
  );
}

describe("extra coverage", () => {
  beforeEach(() => {
    api.mockReset();
  });

  it("covers repo switcher search keyboard and errors", async () => {
    const user = userEvent.setup();
    api.mockResolvedValue([
      repository,
      { ...repository, id: 8, name: "other", fullName: "KHolodilin/other", trackingEnabled: false, visibility: "PRIVATE" },
      { ...repository, id: 9, name: "extra", fullName: "KHolodilin/extra", trackingEnabled: true },
    ]);
    const view = render(
      <MemoryRouter initialEntries={["/repositories/13"]}>
        <Routes>
          <Route
            path="/repositories/:id"
            element={<RepoSwitcher currentId={13} currentLabel="current" hrefFor={(id) => `/repositories/${id}`} />}
          />
        </Routes>
      </MemoryRouter>,
    );
    await user.click(screen.getByLabelText("Switch repository"));
    expect(await screen.findByPlaceholderText("Search repositories")).toBeInTheDocument();
    await user.click(screen.getByLabelText("Switch repository"));
    expect(screen.queryByPlaceholderText("Search repositories")).not.toBeInTheDocument();
    await user.click(screen.getByLabelText("Switch repository"));
    await user.type(screen.getByPlaceholderText("Search repositories"), "zzz");
    expect(screen.getByText("No repositories found")).toBeInTheDocument();
    await user.clear(screen.getByPlaceholderText("Search repositories"));
    await user.type(screen.getByPlaceholderText("Search repositories"), "extra");
    const search = screen.getByPlaceholderText("Search repositories");
    fireEvent.keyDown(search, { key: "ArrowDown" });
    fireEvent.keyDown(search, { key: "ArrowUp" });
    fireEvent.keyDown(search, { key: "Enter" });
    view.unmount();

    api.mockRejectedValueOnce(new Error("switcher down"));
    render(
      <MemoryRouter>
        <RepoSwitcher currentId={13} currentLabel="current" hrefFor={(id) => `/repositories/${id}`} />
      </MemoryRouter>,
    );
    await user.click(screen.getByLabelText("Switch repository"));
    expect(await screen.findByText("switcher down")).toBeInTheDocument();
    fireEvent.keyDown(document, { key: "Escape" });
    fireEvent.mouseDown(document.body);
  });

  it("covers query topic and traffic history branches", async () => {
    const user = userEvent.setup();
    api.mockImplementation(async (path: string) => {
      const url = String(path);
      if (url.includes("/run")) {
        throw new Error("run failed");
      }
      if (url.endsWith("/repositories/13")) {
        return repository;
      }
      if (url.includes("/topics/") && url.includes("history")) {
        return { ...topicHistory, searchStatus: "SUCCESS", missedDates: ["2026-09-22"] };
      }
      if (url.includes("/topics/") && url.includes("results")) {
        throw new Error("no topic snapshot");
      }
      if (url.includes("/search-queries/") && url.includes("history")) {
        return { ...searchHistory, searchStatus: "SUCCESS", enrichmentStatus: "SUCCESS", missedDates: ["2026-09-22"] };
      }
      if (url.includes("/search-queries/") && url.includes("results")) {
        throw new Error("no search snapshot");
      }
      if (url.includes("/search-runs/")) {
        return { ...searchResults, run: { ...searchResults.run, enrichmentStatus: "PARTIAL" } };
      }
      if (url.includes("traffic-history")) {
        return snapshotHistory;
      }
      return [];
    });

    const topicView = renderAt("/repositories/13/topics/outbox?scope=language", <TopicDetailsPage />);
    expect(await screen.findByRole("heading", { name: "outbox" })).toBeInTheDocument();
    expect(screen.getByText("No topic snapshot yet.")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Run now" }));
    expect(await screen.findByText("run failed")).toBeInTheDocument();
    topicView.unmount();

    const queryView = renderAt("/repositories/13/search-queries/5", <QueryDetailsPage />);
    expect(await screen.findByText("No search snapshot yet.")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Run now" }));
    expect(await screen.findByText("run failed")).toBeInTheDocument();
    queryView.unmount();

    const runView = renderAt("/search-runs/9", <SearchResultsPage />);
    expect(await screen.findByText(/Enrichment: PARTIAL/)).toBeInTheDocument();
    runView.unmount();

    const historyView = renderAt("/repositories/13/traffic/history?kind=paths&days=7", <TrafficHistoryPage />);
    expect(await screen.findByRole("heading", { name: "Path Traffic (by day)" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Referrers" }));
    await user.click(screen.getByRole("button", { name: "1d" }));
    await user.click(screen.getByRole("button", { name: "Popular Paths" }));
    await user.click(screen.getByLabelText("Show service pages"));
    historyView.unmount();
  });

  it("covers repository query errors polling and empty repositories", async () => {
    const user = userEvent.setup();
    api.mockImplementation(async (path: string, init?: RequestInit) => {
      const url = String(path);
      if (url.includes("/collect")) {
        throw new Error("collect failed");
      }
      if (url.includes("/search-queries") && init?.method === "POST" && url.endsWith("/search-queries")) {
        throw new Error("create failed");
      }
      if (url.includes("/search-queries/run")) {
        throw new Error("run all failed");
      }
      if (url.includes("/search-queries/") && init?.method === "DELETE") {
        throw new Error("delete failed");
      }
      if (url.includes("/topic-watches/run")) {
        throw new Error("topics failed");
      }
      if (url.endsWith("/repositories/13")) {
        return { ...repository, githubUrl: undefined, defaultBranch: undefined, lastCommitAt: undefined };
      }
      if (url.includes("/traffic?")) {
        return {
          ...traffic,
          lastCollection: {
            ...traffic.lastCollection,
            status: "SUCCESS",
            jobs: [
              { jobType: "TRAFFIC", status: "SUCCESS" },
              { jobType: "CUSTOM_JOB", status: "PLANNED" },
              { jobType: "GROWTH_EVENTS", status: "RUNNING" },
              { jobType: "REFERRERS", status: "FAILED", errorMessage: "rate", completedAt: "2026-09-23T06:01:00Z" },
            ],
          },
        };
      }
      if (url.includes("search-visibility")) {
        return [searchHistory];
      }
      if (url.includes("topics-visibility")) {
        return [topicHistory];
      }
      if (url.includes("growth-events") && !url.includes("settings")) {
        return [];
      }
      if (url.includes("growth-event-settings")) {
        return [{ repositoryId: 13, eventType: "STAR_MILESTONE", enabled: true }];
      }
      if (url.includes("stats-history")) {
        throw new Error("stats down");
      }
      return [];
    });

    const details = renderAt("/repositories/13?tab=search", <RepositoryDetailsPage />);
    expect(await screen.findByPlaceholderText(/transactional outbox/)).toBeInTheDocument();
    await user.type(screen.getByPlaceholderText(/transactional outbox/), "transactional outbox language:java");
    expect(screen.getByText("This search query is already tracked for the repository")).toBeInTheDocument();
    await user.clear(screen.getByPlaceholderText(/transactional outbox/));
    await user.type(screen.getByPlaceholderText(/transactional outbox/), "brand new query");
    await user.click(screen.getByRole("button", { name: "Add query" }));
    expect(await screen.findByText("create failed")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Stats" }));
    expect(await screen.findByText("stats down")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Overview" }));
    expect(screen.getByText(/successful/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Collect now" }));
    expect(await screen.findByText("collect failed")).toBeInTheDocument();
    details.unmount();

    const searchErrors = renderAt("/repositories/13?tab=search", <RepositoryDetailsPage />);
    expect(await screen.findByRole("button", { name: "Delete" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Delete" }));
    expect(await screen.findByText("delete failed")).toBeInTheDocument();
    searchErrors.unmount();

    const topicErrors = renderAt("/repositories/13?tab=topics", <RepositoryDetailsPage />);
    expect(await screen.findByText("outbox")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Run all" }));
    expect(await screen.findByText("topics failed")).toBeInTheDocument();
    topicErrors.unmount();

    api.mockImplementation(async (path: string) => {
      const url = String(path);
      if (url.endsWith("/repositories/13")) {
        return repository;
      }
      if (url.includes("/traffic?")) {
        return {
          ...traffic,
          lastCollection: { ...traffic.lastCollection, status: "RUNNING", jobs: [{ jobType: "TRAFFIC", status: "RUNNING" }] },
        };
      }
      return url.includes("visibility") ? [{ ...searchHistory, searchStatus: "READY" }] : [];
    });
    const running = renderAt("/repositories/13?tab=overview", <RepositoryDetailsPage />);
    expect(await screen.findByText(/RUNNING/)).toBeInTheDocument();
    running.unmount();

    api.mockResolvedValue([]);
    renderAt("/repositories", <RepositoriesPage />);
    expect(await screen.findByText(/No repositories yet/)).toBeInTheDocument();
  });

  it("covers growth event form save error and stats panel", async () => {
    const user = userEvent.setup();
    api.mockRejectedValueOnce(new Error("event save failed"));
    render(
      <GrowthEventsPanel repositoryId={13} events={[]} onChanged={vi.fn()} />,
    );
    await user.click(screen.getByRole("button", { name: "+ Add event" }));
    await user.type(screen.getByLabelText("Title"), "Posted");
    await user.selectOptions(screen.getByLabelText("Type"), "CUSTOM");
    await user.type(screen.getByLabelText("URL"), "https://example.com");
    await user.type(screen.getByLabelText("Description"), "note");
    await user.click(screen.getByRole("button", { name: "Save" }));
    expect(await screen.findByText("event save failed")).toBeInTheDocument();

    api.mockResolvedValueOnce({
      repositoryId: 13,
      period: "30d",
      points: [{ date: "2026-09-23", stars: 21, forks: 16, watchers: 0, contributors: 7 }],
    });
    const stats = render(
      <MemoryRouter>
        <RepositoryStatsPanel repositoryId={13} period="30d" onPeriod={vi.fn()} />
      </MemoryRouter>,
    );
    expect(await screen.findByText("Daily totals")).toBeInTheDocument();
    stats.unmount();
  });

  it("edits a manual growth event and sorts search topic tables", async () => {
    const user = userEvent.setup();
    const onChanged = vi.fn();
    api.mockResolvedValue({});
    render(
      <GrowthEventsPanel
        repositoryId={13}
        events={[growthEvent, { ...growthEvent, id: 2, source: "GITHUB", title: "Stars", category: "MILESTONE" }]}
        onChanged={onChanged}
      />,
    );
    await user.click(screen.getByText("LinkedIn launch"));
    await user.click(screen.getAllByRole("button", { name: "Edit" })[0]);
    await user.clear(screen.getByLabelText("Title"));
    await user.type(screen.getByLabelText("Title"), "Updated");
    await user.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(onChanged).toHaveBeenCalled());

    api.mockImplementation(async (path: string) => {
      const url = String(path);
      if (url.endsWith("/repositories/13")) {
        return repository;
      }
      if (url.includes("/traffic?")) {
        return traffic;
      }
      if (url.includes("search-visibility")) {
        return [searchHistory, { ...searchHistory, query: { ...searchHistory.query, id: 6, name: "other", query: "other" } }];
      }
      if (url.includes("topics-visibility")) {
        return [topicHistory, { ...topicHistory, watch: { ...topicHistory.watch, id: 8, topic: "kafka" } }];
      }
      if (url.includes("growth-events")) {
        return [];
      }
      if (url.includes("growth-event-settings")) {
        return [{ repositoryId: 13, eventType: "STAR_MILESTONE", enabled: true }];
      }
      return [];
    });
    const details = renderAt("/repositories/13?tab=search", <RepositoryDetailsPage />);
    expect(await screen.findByText("transactional outbox language:java")).toBeInTheDocument();
    for (const name of ["Search Query", "Rank", "Change", "7d", "30d", "Best", "Results", "Updated"]) {
      await user.click(screen.getAllByRole("button", { name: new RegExp(`^${name}`) })[0]);
    }
    await user.click(screen.getByRole("button", { name: "Topics Visibility" }));
    expect(await screen.findByText("kafka")).toBeInTheDocument();
    for (const name of ["Topic", "Rank", "Change", "7d", "30d", "Best", "Results", "Updated"]) {
      await user.click(screen.getAllByRole("button", { name: new RegExp(`^${name}`) })[0]);
    }
    details.unmount();
  });
});
