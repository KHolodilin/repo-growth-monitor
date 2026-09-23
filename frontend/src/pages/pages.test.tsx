import type { ReactNode } from "react";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import App from "../App";
import {
  dashboardReady,
  growthEvent,
  repository,
  searchHistory,
  searchResults,
  snapshotHistory,
  topicHistory,
  topicResults,
  traffic,
} from "../test/fixtures";
import { DashboardPage } from "./DashboardPage";
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

function json(data: unknown) {
  return Promise.resolve(data);
}

function renderPath(path: string, ui: ReactNode) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/" element={<div>home</div>} />
        <Route path="/dashboard" element={<div>home</div>} />
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

describe("pages", () => {
  beforeEach(() => {
    api.mockReset();
  });

  it("renders dashboard ready state and sorts repositories", async () => {
    const user = userEvent.setup();
    api.mockImplementation((path: string) => {
      if (String(path).includes("/dashboard")) {
        return json(dashboardReady);
      }
      return json({});
    });
    render(
      <MemoryRouter>
        <DashboardPage />
      </MemoryRouter>,
    );
    expect(await screen.findByText("KHolodilin/spring-transactional-outbox-kafka")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: /Growth/ }));
    await user.click(screen.getByRole("button", { name: /Collect now/ }));
    await waitFor(() => expect(api).toHaveBeenCalledWith("/api/v1/repositories/13/collect", { method: "POST" }));
    await user.click(screen.getByRole("button", { name: "7d" }));
  });

  it("shows empty and error dashboard states", async () => {
    api.mockResolvedValueOnce({ ...dashboardReady, state: "NO_REPOSITORIES", repositories: [] });
    const { unmount } = render(
      <MemoryRouter>
        <DashboardPage />
      </MemoryRouter>,
    );
    expect(await screen.findByText(/No repositories tracked yet/)).toBeInTheDocument();
    unmount();
    api.mockRejectedValueOnce(new Error("down"));
    render(
      <MemoryRouter>
        <DashboardPage />
      </MemoryRouter>,
    );
    expect(await screen.findByText("down")).toBeInTheDocument();
  });

  it("lists repositories and toggles tracking", async () => {
    const user = userEvent.setup();
    api.mockImplementation((path: string, init?: RequestInit) => {
      if (String(path).includes("/tracking")) {
        return json({ ...repository, trackingEnabled: false });
      }
      return json([repository, { ...repository, id: 8, name: "other", fullName: "KHolodilin/other", trackingEnabled: false }]);
    });
    renderPath("/repositories", <RepositoriesPage />);
    expect(await screen.findByText(repository.fullName)).toBeInTheDocument();
    await user.click(screen.getAllByRole("checkbox")[0]);
    await user.click(screen.getByRole("button", { name: /Refresh from GitHub/ }));
    api.mockRejectedValueOnce(new Error("token"));
    await user.click(screen.getByRole("button", { name: /Refresh from GitHub/ }));
    expect(await screen.findByText("token")).toBeInTheDocument();
  });

  it("walks repository tabs, search, topics and collect", async () => {
    const user = userEvent.setup();
    api.mockImplementation((path: string, init?: RequestInit) => {
      const url = String(path);
      if (url.endsWith("/repositories/13")) {
        return json(repository);
      }
      if (url.includes("/traffic?")) {
        return json(traffic);
      }
      if (url.includes("search-visibility")) {
        return json([searchHistory]);
      }
      if (url.includes("topics-visibility")) {
        return json([topicHistory, { ...topicHistory, watch: { ...topicHistory.watch, id: 8, topic: "kafka" }, currentRank: null }]);
      }
      if (url.includes("growth-events") && !url.includes("settings") && init?.method !== "POST") {
        return json([growthEvent]);
      }
      if (url.includes("growth-event-settings")) {
        return json([{ repositoryId: 13, eventType: "STAR_MILESTONE", enabled: true }]);
      }
      if (url.includes("stats-history")) {
        return json({
          repositoryId: 13,
          period: "30d",
          points: [{ date: "2026-09-23", stars: 21, forks: 16, watchers: 0, contributors: 7 }],
        });
      }
      if (url.includes("/repositories?refresh=false")) {
        return json([repository, { ...repository, id: 8, name: "other", fullName: "KHolodilin/other", visibility: "PRIVATE" }]);
      }
      if (url.includes("/collect") || url.includes("/run") || url.includes("search-queries")) {
        return json({});
      }
      return json([]);
    });
    renderPath("/repositories/13", <RepositoryDetailsPage />);
    expect(await screen.findByText("Github Traffic")).toBeInTheDocument();
    await user.click(screen.getByLabelText("Switch repository"));
    expect(await screen.findByPlaceholderText("Search repositories")).toBeInTheDocument();
    expect(await screen.findByText("other")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "legend" }));
    await user.click(screen.getByRole("button", { name: "mark" }));
    expect(await screen.findByText("Growth event")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Close" }));
    await user.click(screen.getByRole("button", { name: "GitHub" }));
    await user.click(screen.getByRole("button", { name: "Promotion" }));
    await user.click(screen.getByRole("button", { name: "Overview" }));
    expect(screen.getByText("Collection Status")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Stats" }));
    expect(await screen.findByText("Daily totals")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Search Visibility" }));
    await user.type(screen.getByPlaceholderText(/transactional outbox/), "new query language:java");
    await user.click(screen.getByRole("button", { name: "Add query" }));
    await user.click(screen.getAllByRole("button", { name: "Run" })[0]);
    await user.click(screen.getByRole("button", { name: "Run all" }));
    await user.click(screen.getByRole("button", { name: "Delete" }));
    await user.click(screen.getByRole("button", { name: "Topics Visibility" }));
    expect(await screen.findByText("outbox")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Java" }));
    await user.click(screen.getByRole("button", { name: "All" }));
    await user.click(screen.getAllByRole("button", { name: "Run" })[0]);
    await user.click(screen.getByRole("button", { name: "Run all" }));
    await user.click(screen.getByRole("button", { name: "Growth Events" }));
    expect(await screen.findByText("Recent Growth Events")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "+ Add event" }));
    await user.type(screen.getByLabelText("Title"), "Posted");
    await user.click(screen.getByRole("button", { name: "Save" }));
    await user.click(screen.getByRole("button", { name: "Collect now" }));
  }, 20000);

  it("shows collection failure and load error on repository page", async () => {
    api.mockImplementation((path: string) => {
      const url = String(path);
      if (url.endsWith("/repositories/13")) {
        return json({ ...repository, topics: [], language: undefined, health: { discoverability: [], communityStandards: [] } });
      }
      if (url.includes("/traffic?")) {
        return json({
          ...traffic,
          lastCollection: {
            ...traffic.lastCollection,
            status: "FAILED",
            jobs: [{ jobType: "TRAFFIC", status: "FAILED", errorMessage: "rate limit", completedAt: "2026-09-23T06:01:00Z" }],
          },
        });
      }
      if (url.includes("visibility")) {
        return json([]);
      }
      return json([]);
    });
    renderPath("/repositories/13?tab=overview", <RepositoryDetailsPage />);
    expect(await screen.findByText("Collection failed")).toBeInTheDocument();
    expect(screen.getByText("No topics yet.")).toBeInTheDocument();

    api.mockRejectedValueOnce(new Error("missing"));
    renderPath("/repositories/13", <RepositoryDetailsPage />);
    expect(await screen.findByText("missing")).toBeInTheDocument();
  });

  it("covers topic query search-run and history pages plus app routes", async () => {
    const user = userEvent.setup();
    api.mockImplementation((path: string) => {
      const url = String(path);
      if (url.endsWith("/repositories/13") || url.includes("/repositories?refresh")) {
        return json(url.includes("refresh") ? [repository] : repository);
      }
      if (url.includes("/dashboard")) {
        return json(dashboardReady);
      }
      if (url.includes("/topics/") && url.includes("history")) {
        return json({ ...topicHistory, missedDates: ["2026-09-22"] });
      }
      if (url.includes("/topics/") && url.includes("results")) {
        return json(topicResults);
      }
      if (url.includes("/search-queries/") && url.includes("history")) {
        return json({ ...searchHistory, missedDates: ["2026-09-22"] });
      }
      if (url.includes("/search-queries/") && url.includes("results")) {
        return json(searchResults);
      }
      if (url.includes("/search-runs/")) {
        return json(searchResults);
      }
      if (url.includes("traffic-history")) {
        return json(snapshotHistory);
      }
      if (url.includes("/run")) {
        return json({});
      }
      return json([]);
    });
    const topicView = renderPath("/repositories/13/topics/outbox", <TopicDetailsPage />);
    expect(await screen.findByRole("heading", { name: "outbox" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Java" }));
    await user.click(screen.getByRole("button", { name: "Run now" }));
    topicView.unmount();

    const queryView = renderPath("/repositories/13/search-queries/5", <QueryDetailsPage />);
    expect(await screen.findByText(/transactional outbox/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Run now" }));
    queryView.unmount();

    const runView = renderPath("/search-runs/9", <SearchResultsPage />);
    expect(await screen.findByText(repository.fullName)).toBeInTheDocument();
    runView.unmount();

    const historyView = renderPath("/repositories/13/traffic/history?kind=paths&days=7", <TrafficHistoryPage />);
    expect(await screen.findByRole("heading", { name: "Path Traffic (by day)" })).toBeInTheDocument();
    historyView.unmount();

    render(
      <MemoryRouter initialEntries={["/unknown"]}>
        <App />
      </MemoryRouter>,
    );
    expect(await screen.findByText(repository.fullName)).toBeInTheDocument();
  });
});
