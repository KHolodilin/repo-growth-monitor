import type { ReactNode } from "react";
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { Layout } from "../components/Layout";
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
    document.cookie = "search_visibility_queries_13=; path=/; max-age=0";
    document.cookie = "topics_visibility_topics_13=; path=/; max-age=0";
    document.cookie = "topics_visibility_scope_13=; path=/; max-age=0";
    document.cookie = "topics_visibility_scope_8=; path=/; max-age=0";
  });

  it("renders dashboard ready state and sorts repositories", async () => {
    const user = userEvent.setup();
    api.mockImplementation((path: string) => {
      if (String(path).includes("/dashboard")) {
        return json(dashboardReady);
      }
      if (String(path).includes("growth-events")) {
        return Promise.reject(new Error("events down"));
      }
      return json({});
    });
    render(
      <MemoryRouter>
        <Routes>
          <Route element={<Layout />}>
            <Route path="/" element={<DashboardPage />} />
          </Route>
        </Routes>
      </MemoryRouter>,
    );
    expect(await screen.findByText("KHolodilin/spring-transactional-outbox-kafka")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Forks" })).toBeInTheDocument();
    expect(screen.getByText("16")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Speed" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: /Growth/ }));
    await user.click(screen.getByRole("button", { name: "Collect 1 repository" }));
    await waitFor(() => expect(api).toHaveBeenCalledWith("/api/v1/repositories/13/collect", { method: "POST" }));
    await user.click(screen.getByRole("button", { name: "7d" }));
  });

  it("shows empty and error dashboard states", async () => {
    api.mockImplementation((path: string) => {
      if (String(path).includes("/system/status")) {
        return json({
          tokenState: "MISSING",
          rateLimitReset: null,
          planner: { timezone: "UTC", from: "10:00", to: "18:00", open: false },
        });
      }
      if (String(path).includes("/dashboard")) {
        return json({ ...dashboardReady, state: "NO_REPOSITORIES", repositories: [], traffic: [] });
      }
      return json({});
    });
    const { unmount } = render(
      <MemoryRouter>
        <DashboardPage />
      </MemoryRouter>,
    );
    expect(await screen.findByText("Application is running.")).toBeInTheDocument();
    expect(screen.getByText(/Read permissions: Metadata, Contents, Administration, Issues, Pull requests/)).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Select repositories" })).not.toBeInTheDocument();
    unmount();
    api.mockImplementation((path: string) => {
      if (String(path).includes("/system/status")) {
        return json({ tokenState: "OK", rateLimitReset: null, planner: null });
      }
      return Promise.reject(new Error("down"));
    });
    render(
      <MemoryRouter>
        <DashboardPage />
      </MemoryRouter>,
    );
    expect(await screen.findByText("down")).toBeInTheDocument();
  });

  it("lists repositories and toggles tracking", async () => {
    const user = userEvent.setup();
    api.mockImplementation((path: string, _init?: RequestInit) => {
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
        const kafka = {
          ...topicHistory,
          watch: { ...topicHistory.watch, id: 8, topic: "kafka" },
          currentRank: null,
          missedDates: undefined,
        };
        return json([topicHistory, kafka]);
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
          totals: {
            stars: { value: 21, absoluteChange: null, growthPercent: null },
            forks: { value: 16, absoluteChange: null, growthPercent: null },
            watchers: { value: 0, absoluteChange: null, growthPercent: null },
            contributors: { value: 7, absoluteChange: null, growthPercent: null },
          },
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
    expect(await screen.findByRole("link", { name: "GitHub Traffic" })).toHaveAttribute(
      "href",
      "https://github.com/KHolodilin/spring-transactional-outbox-kafka/graphs/traffic",
    );
    expect(screen.getByRole("link", { name: "GitHub Traffic" }).parentElement).toHaveClass("ml-auto");
    const crumb = screen.getByRole("navigation", { name: "Breadcrumb" });
    expect(crumb).toHaveTextContent("Portfolio");
    expect(crumb).toHaveTextContent(repository.fullName);
    expect(crumb).toHaveTextContent("Traffic");
    expect(screen.getByRole("tooltip", { hidden: true, name: repository.description })).toHaveClass(
      "delay-300",
      "w-72",
      "whitespace-normal",
    );
    expect(screen.getByRole("button", { name: "Discoverability 1/2" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Community Standards 1/1" })).toBeInTheDocument();
    expect(screen.queryByText("1 / 2")).not.toBeInTheDocument();
    expect(screen.getByText("↑ 25%")).toBeInTheDocument();
    expect(screen.getByText("↓ 50%")).toBeInTheDocument();
    expect(screen.getAllByText("vs prev 30d")).toHaveLength(3);
    await user.click(screen.getByLabelText("Switch repository"));
    expect(await screen.findByPlaceholderText("Search repositories")).toBeInTheDocument();
    expect(await screen.findByText("other")).toBeInTheDocument();
    await user.click(screen.getByRole("option", { name: repository.name }));
    await user.click(screen.getByRole("button", { name: "legend" }));
    await user.click(screen.getByRole("button", { name: "mark" }));
    expect(await screen.findByText("Growth event")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Edit" }));
    await user.click(screen.getByRole("button", { name: "mark" }));
    api.mockResolvedValueOnce({});
    await user.click(screen.getByRole("button", { name: "Delete" }));
    await user.click(screen.getAllByRole("button", { name: "All events" })[0]);
    await user.click(screen.getByRole("menuitemradio", { name: "GitHub events" }));
    await user.click(screen.getByRole("button", { name: "GitHub events" }));
    await user.click(screen.getByRole("menuitemradio", { name: "Promotion events" }));
    await user.click(screen.getByRole("button", { name: "Discoverability 1/2" }));
    expect(screen.queryByRole("heading", { name: "Repository Health" })).not.toBeInTheDocument();
    expect(screen.getByText("Description")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Community Standards 1/1" }));
    expect(screen.getByText("README")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Overview" }));
    const about = screen.getByRole("heading", { name: "About this repository" });
    const topicsHeading = screen.getByRole("heading", { name: "Topics" });
    const overviewHeading = screen.getByRole("heading", { level: 2, name: "Overview" });
    const collection = screen.getByRole("heading", { name: "Collection Status" });
    expect(about.compareDocumentPosition(topicsHeading) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(topicsHeading.compareDocumentPosition(overviewHeading) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(overviewHeading.compareDocumentPosition(collection) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(overviewHeading.closest(".grid")).toBe(collection.closest(".grid"));
    expect(about.parentElement).toHaveTextContent(repository.description ?? "");
    expect(about.querySelector("svg")).not.toBeNull();
    expect(screen.getByText("kafka")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Open repository" })).toHaveAttribute("target", "_blank");
    expect(screen.getByText("Description").parentElement).toHaveClass("text-emerald-700");
    expect(screen.getByText("Topics", { selector: "span" }).parentElement).toHaveClass("text-muted-foreground");
    expect(collection.parentElement?.querySelector(".text-emerald-600")).not.toBeNull();
    await user.click(screen.getByLabelText("Switch repository"));
    await user.click(await screen.findByRole("option", { name: repository.name }));
    await user.click(screen.getByRole("button", { name: "Stats" }));
    expect(await screen.findByText("Daily totals")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Search Visibility" }));
    const syntax = screen.getByRole("link", { name: "Search syntax tips" });
    expect(syntax).toHaveAttribute(
      "href",
      "https://docs.github.com/en/search-github/searching-on-github/searching-for-repositories",
    );
    expect(syntax).toHaveAttribute("target", "_blank");
    expect(syntax).toHaveAttribute("rel", "noreferrer");
    expect(syntax.parentElement).toHaveTextContent("Search Queries");
    expect(syntax.parentElement?.querySelector("input")).toBeNull();
    expect(screen.getByText("1 / 50")).toBeInTheDocument();
    expect(screen.getByText("49 available")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "mark-empty" }));
    expect(screen.queryByRole("heading", { name: "Growth event" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "mark" }));
    expect(await screen.findByText("A match in time does not prove a cause.")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Edit" }));
    await user.click(screen.getByRole("button", { name: "mark" }));
    const dialog = screen.getByRole("heading", { name: "Growth event" }).closest(".fixed");
    api.mockResolvedValueOnce({});
    await user.click(within(dialog as HTMLElement).getByRole("button", { name: "Delete" }));
    await waitFor(() => expect(screen.queryByText("A match in time does not prove a cause.")).not.toBeInTheDocument());
    expect(await screen.findByText(searchHistory.query.name)).toBeInTheDocument();
    fireEvent.mouseEnter(screen.getByText(searchHistory.query.name));
    fireEvent.mouseLeave(screen.getByText(searchHistory.query.name));
    await user.click(screen.getByRole("checkbox", { name: `Chart line for ${searchHistory.query.name}` }));
    expect(screen.getByText("Select search queries from the table to display rank history")).toBeInTheDocument();
    await user.click(screen.getByRole("checkbox", { name: `Chart line for ${searchHistory.query.name}` }));
    for (const name of ["Search Query", "Rank", "Change", "7d", "30d", "Best", "Results", "Updated"]) {
      await user.click(screen.getAllByRole("button", { name: new RegExp(`^${name}`) })[0]);
    }
    await user.type(screen.getByPlaceholderText(/transactional outbox/), "new query language:java");
    await user.click(screen.getByRole("button", { name: "Add query" }));
    await user.click(screen.getAllByRole("button", { name: "Run" })[0]);
    await user.click(screen.getByRole("button", { name: "Run all" }));
    await user.click(screen.getByRole("button", { name: "Delete" }));
    await user.click(screen.getByRole("button", { name: "Topics Visibility" }));
    expect(await screen.findByText("Tracked topics")).toBeInTheDocument();
    expect(screen.getByText("outbox")).toBeInTheDocument();
    const scopeBar = screen.getByRole("button", { name: "All" }).parentElement as HTMLElement;
    expect(scopeBar).toHaveTextContent("Java");
    expect(scopeBar).not.toHaveTextContent("7d");
    expect(scopeBar.parentElement?.contains(screen.getAllByRole("button", { name: "7d" })[0])).toBe(true);
    const outboxLine = screen.getByRole("checkbox", { name: "Chart line for outbox" });
    await user.click(outboxLine);
    await user.click(outboxLine);
    fireEvent.mouseEnter(screen.getByText("outbox"));
    fireEvent.mouseLeave(screen.getByText("outbox"));
    await user.click(screen.getByRole("button", { name: "mark-empty" }));
    await user.click(screen.getByRole("button", { name: "mark" }));
    expect(await screen.findByText("A match in time does not prove a cause.")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Close" }));
    await user.click(screen.getByRole("button", { name: "mark" }));
    await user.click(screen.getByRole("button", { name: "Edit" }));
    await user.click(screen.getByRole("button", { name: "mark" }));
    const topicDialog = screen.getByRole("heading", { name: "Growth event" }).closest(".fixed");
    api.mockResolvedValueOnce({});
    await user.click(within(topicDialog as HTMLElement).getByRole("button", { name: "Delete" }));
    await waitFor(() => expect(screen.queryByText("A match in time does not prove a cause.")).not.toBeInTheDocument());
    const topicHeader = await screen.findByRole("checkbox", { name: "Chart lines", checked: true });
    await user.click(topicHeader);
    expect(screen.getByText("Select topics from the table to display rank history")).toBeInTheDocument();
    await user.click(topicHeader);
    for (const name of ["Topic", "Rank", "Change", "7d", "30d", "Best", "Results", "Updated"]) {
      const buttons = screen.getAllByRole("button", { name: new RegExp(`^${name}`) });
      await user.click(buttons[buttons.length - 1]);
    }
    await user.click(screen.getByRole("button", { name: "Java" }));
    await user.click(screen.getByLabelText("Switch repository"));
    await user.click(await screen.findByRole("option", { name: repository.name }));
    await user.click(screen.getByRole("button", { name: "All" }));
    await user.click(screen.getAllByRole("button", { name: "Run" })[0]);
    await user.click(screen.getByRole("button", { name: "Run all" }));
    await user.click(screen.getByRole("button", { name: "Growth Events" }));
    expect(await screen.findByText("Recent Growth Events")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "+ Add event" }));
    await user.type(screen.getByLabelText("Title"), "Posted");
    await user.click(screen.getByRole("button", { name: "Save" }));
  }, 20000);

  it("drops a topic event response that arrives after the period changes", async () => {
    const user = userEvent.setup();
    const pending: { resolve: (value: unknown) => void; reject: (error: Error) => void }[] = [];
    api.mockImplementation((path: string) => {
      const url = String(path);
      if (url.endsWith("/repositories/13")) {
        return json(repository);
      }
      if (url.includes("/traffic?")) {
        return json(traffic);
      }
      if (url.includes("topics-visibility")) {
        return json([topicHistory]);
      }
      if (url.includes("growth-events") && !url.includes("settings")) {
        return new Promise((resolve, reject) => {
          pending.push({ resolve, reject });
        });
      }
      return json([]);
    });
    renderPath("/repositories/13?tab=topics", <RepositoryDetailsPage />);
    expect(await screen.findByText("Tracked topics")).toBeInTheDocument();
    await waitFor(() => expect(pending).toHaveLength(1));
    await user.click(screen.getAllByRole("button", { name: "7d" })[0]);
    await waitFor(() => expect(pending).toHaveLength(2));
    pending[0].resolve([]);
    await user.click(screen.getAllByRole("button", { name: "30d" })[0]);
    await waitFor(() => expect(pending).toHaveLength(3));
    pending[1].reject(new Error("late"));
    await act(async () => {
      pending[2].resolve({});
    });
    expect(screen.getByText("outbox")).toBeInTheDocument();
  });

  it("shows dashes when overview fields and topics were never stored", async () => {
    api.mockImplementation((path: string) => {
      const url = String(path);
      if (url.endsWith("/repositories/13")) {
        return json({
          ...repository,
          topics: undefined,
          language: undefined,
          defaultBranch: undefined,
          lastCommitAt: undefined,
          githubUpdatedAt: undefined,
          githubUrl: undefined,
        });
      }
      if (url.includes("/traffic?")) {
        return json({
          ...traffic,
          lastCollection: {
            ...traffic.lastCollection,
            status: "PARTIAL",
            completedAt: null,
            jobs: [{ jobType: "MYSTERY", status: "PLANNED" }],
          },
        });
      }
      return json([]);
    });
    renderPath("/repositories/13?tab=overview", <RepositoryDetailsPage />);
    expect(await screen.findByText("No topics yet.")).toBeInTheDocument();
    expect(screen.getAllByText("—").length).toBeGreaterThanOrEqual(4);
    expect(screen.getByRole("link", { name: "Open repository" })).toHaveAttribute(
      "href",
      `https://github.com/${repository.fullName}`,
    );
    expect(screen.getByText("MYSTERY")).toBeInTheDocument();
    expect(screen.queryByText("Collection failed")).not.toBeInTheDocument();
  });

  it("shows collection failure and load error on repository page", async () => {
    const user = userEvent.setup();
    api.mockImplementation((path: string) => {
      const url = String(path);
      if (url.endsWith("/repositories/13")) {
        return json({
          ...repository,
          description: "  ",
          githubUrl: undefined,
          topics: [],
          language: undefined,
          health: { discoverability: [], communityStandards: [] },
        });
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
    expect(screen.queryByRole("heading", { name: "About this repository" })).not.toBeInTheDocument();
    expect(screen.queryByRole("tooltip", { hidden: true })).not.toBeInTheDocument();
    expect(screen.getByText("No topics yet.")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Topics Visibility" }));
    expect(await screen.findByText(/No topics yet. Add topics/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "All" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "1d" })).toBeInTheDocument();

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
        return json({ ...topicHistory, missedDates: [new Date().toISOString().slice(0, 10)] });
      }
      if (url.includes("/topics/") && url.includes("results")) {
        return json(topicResults);
      }
      if (url.includes("/search-queries/") && url.includes("history")) {
        return json({ ...searchHistory, missedDates: [new Date().toISOString().slice(0, 10)] });
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
    expect(screen.queryByText(/Not collected on/)).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Java" }));
    await user.click(screen.getByRole("button", { name: "Run now" }));
    topicView.unmount();

    const queryView = renderPath("/repositories/13/search-queries/5", <QueryDetailsPage />);
    expect(await screen.findByText(/transactional outbox/)).toBeInTheDocument();
    expect(screen.queryByText(/Not collected on/)).not.toBeInTheDocument();
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

  it("keeps the rank chart when event markers fail or arrive late", async () => {
    api.mockImplementation((path: string) => {
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
      if (url.includes("growth-events") && url.includes("period=all")) {
        return Promise.reject(new Error("down"));
      }
      if (url.includes("growth-events")) {
        return json([growthEvent]);
      }
      return json([]);
    });
    const rejected = renderPath("/repositories/13?tab=search", <RepositoryDetailsPage />);
    expect(await screen.findByText(searchHistory.query.name)).toBeInTheDocument();
    rejected.unmount();

    api.mockImplementation((path: string) => {
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
      if (url.includes("growth-events") && url.includes("period=all")) {
        return json({ nope: true });
      }
      return json([]);
    });
    const odd = renderPath("/repositories/13?tab=search", <RepositoryDetailsPage />);
    expect(await screen.findByText(searchHistory.query.name)).toBeInTheDocument();
    odd.unmount();

    const pending: ((value: unknown) => void)[] = [];
    api.mockImplementation((path: string) => {
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
      if (url.includes("growth-events") && url.includes("period=all")) {
        return new Promise((resolve) => pending.push(resolve));
      }
      return json([]);
    });
    const late = renderPath("/repositories/13?tab=search", <RepositoryDetailsPage />);
    expect(await screen.findByText(searchHistory.query.name)).toBeInTheDocument();
    late.unmount();
    pending.forEach((resolve) => resolve([growthEvent]));
    await act(async () => {
      await Promise.resolve();
    });

    const failing: ((reason?: unknown) => void)[] = [];
    api.mockImplementation((path: string) => {
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
      if (url.includes("growth-events") && url.includes("period=all")) {
        return new Promise((_resolve, reject) => failing.push(reject));
      }
      return json([]);
    });
    const abandoned = renderPath("/repositories/13?tab=search", <RepositoryDetailsPage />);
    expect(await screen.findByText(searchHistory.query.name)).toBeInTheDocument();
    abandoned.unmount();
    failing.forEach((reject) => reject(new Error("down")));
    await act(async () => {
      await Promise.resolve();
    });
  });

  it("checks all search chart lines, some of them, and none", async () => {
    const user = userEvent.setup();
    const second = {
      ...searchHistory,
      query: { ...searchHistory.query, id: 6, name: "kafka language:java", query: "kafka language:java" },
      currentRank: 8,
    };
    api.mockImplementation((path: string) => {
      const url = String(path);
      if (url.endsWith("/repositories/13")) {
        return json(repository);
      }
      if (url.includes("/traffic?")) {
        return json(traffic);
      }
      if (url.includes("search-visibility")) {
        return json([searchHistory, second]);
      }
      return json([]);
    });
    document.cookie = "search_visibility_queries_13=; path=/; max-age=0";
    renderPath("/repositories/13?tab=search", <RepositoryDetailsPage />);
    expect(await screen.findByText("2 / 50")).toBeInTheDocument();
    const header = await screen.findByRole("checkbox", { name: "Chart lines", checked: true });
    await user.click(header);
    expect(screen.getByText("Select search queries from the table to display rank history")).toBeInTheDocument();
    await user.click(screen.getByRole("checkbox", { name: "Chart line for kafka language:java" }));
    expect((header as HTMLInputElement).indeterminate).toBe(true);
    await user.click(header);
    expect(header).toBeChecked();
    expect(screen.getByRole("checkbox", { name: `Chart line for ${searchHistory.query.name}` })).toBeChecked();
  });
});
