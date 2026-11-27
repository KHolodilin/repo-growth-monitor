import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import App from "../App";
import { anonymousMe } from "../lib/auth";
import {
  relativeRepositoryPath,
  adaptPublicDashboard,
  publicBase,
  publicEvents,
  historyRows,
  publicRepositoryRows,
  publicScope,
  type PublicOverview,
  type StatsResponse,
  type TrafficResponse,
} from "../lib/publicDashboard";
import { readPublicPreference, writePublicPreference } from "../lib/publicPrefs";
import { snapshotChartOption, snapshotSeriesKey } from "../lib/snapshotChart";
import { chartCsv } from "../lib/chartExport";
import { withCollectionWarning } from "../lib/chartPresentation";
import type { SnapshotHistory } from "../lib/api";
import { dashboardReady, repository, searchHistory, traffic } from "../test/fixtures";
import { respondTrafficResources } from "../test/trafficResources";
import { emptyCursorPage, isOwnersCatalog } from "../test/repoRoutes";
import { respondKpi } from "../test/kpiFixtures";

const { api, loadMe } = vi.hoisted(() => ({ api: vi.fn(), loadMe: vi.fn() }));
vi.mock("../lib/api", async (original) => ({
  ...(await original<typeof import("../lib/api")>()),
  api,
}));
vi.mock("../lib/auth", async (original) => ({
  ...(await original<typeof import("../lib/auth")>()),
  loadMe,
}));

const overview: PublicOverview = {
  owner: { id: "1", login: "Alpha", type: "USER" },
  canonicalPath: "/Alpha",
  repositoryCount: 1,
  modules: { traffic: true, referrers: true, paths: true, search: true, topics: true },
  freshness: { state: "READY" },
};
const emptyMetric = {
  value: 0,
  growthPercent: null,
  absoluteChange: null,
  complete: true,
};
const totals = {
  stars: emptyMetric,
  forks: emptyMetric,
  watchers: emptyMetric,
  contributors: emptyMetric,
  views: emptyMetric,
  visitors: emptyMetric,
  clones: emptyMetric,
  uniqueCloners: emptyMetric,
} satisfies StatsResponse["totals"] & TrafficResponse["totals"];

const publishedRepo = {
  ...repository,
  id: 13,
  name: "project",
  fullName: "Alpha/project",
  owner: { ...repository.owner, login: "Alpha" },
};

const alphaDashboard = {
  ...dashboardReady,
  period: "30d",
  repositories: [
    {
      ...dashboardReady.repositories[0],
      id: "9007199254740993",
      fullName: "Alpha/project",
      stars: 0,
      forks: 0,
    },
  ],
};

function respond(path: string) {
  const url = String(path);
  if (url.startsWith("/api/v1/owners?login=")) {
    const login = new URL(url, "http://local.test").searchParams.get("login") ?? "Alpha";
    return { items: [{ ownerId: "1", login }], nextCursor: null };
  }
  if (url.includes("/api/v1/owners/1/repositories?name=")) {
    return {
      items: [
        {
          repositoryId: "13",
          ownerId: "1",
          name: "project",
          fullName: "Alpha/project",
          description: null,
          language: "Java",
          archived: false,
          fork: false,
          activityStatus: "ACTIVE",
          modules: {
            dashboard: true,
            traffic: true,
            referrers: true,
            paths: true,
            topics: true,
          },
        },
      ],
      nextCursor: null,
    };
  }
  if (url === "/api/v1/owners/1" || url.match(/^\/api\/v1\/owners\/1(\?|$)/)) {
    return { ownerId: "1", login: "Alpha", ownerType: "USER" };
  }
  if (url.includes("/api/v1/dashboard?")) {
    return alphaDashboard;
  }
  if (url.includes("/api/v1/owners/1/growth-events")) {
    return [];
  }
  if (isOwnersCatalog(url)) return emptyCursorPage;
  if (url.endsWith("/repositories/13")) return publishedRepo;
  if (url.includes("/admin/repositories?") || url.includes("/admin/repositories?refresh"))
    return [publishedRepo];
  const trafficBundle = respondTrafficResources(url, { ...traffic, repository: publishedRepo });
  if (trafficBundle !== undefined) return trafficBundle;
  if (url.includes("search-queries")) return [{ ...searchHistory, repositoryId: 13 }];
  if (url.includes("topic-watches")) return [];
  if (
    url.includes("growth-events") ||
    url.includes("growth-event-settings") ||
    url.includes("stats-history")
  )
    return [];
  const kpi = respondKpi(url);
  if (kpi !== undefined) {
    return kpi;
  }
  throw Error("Unexpected request: " + url);
}

function page(path = "/Alpha") {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <App />
    </MemoryRouter>,
  );
}

beforeEach(() => {
  localStorage.clear();
  api.mockReset();
  loadMe.mockReset();
  loadMe.mockResolvedValue(anonymousMe);
  api.mockImplementation((path: string) => Promise.resolve(respond(path)));
});

describe("public dashboards", () => {
  it("uses the resource dashboard without identity or management requests", async () => {
    page();
    expect(await screen.findByRole("navigation", { name: "Breadcrumb" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Alpha/project" })).toHaveAttribute(
      "href",
      "/Alpha/project",
    );
    expect(screen.queryByText("Portfolio")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /collect/i })).not.toBeInTheDocument();
    expect(screen.getByRole("group", { name: "Alpha analytics" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Search Visibility" })).not.toBeInTheDocument();
    expect(
      api.mock.calls.every(([path]) => {
        const url = String(path);
        return (
          url.startsWith("/api/v1/owners") ||
          url.startsWith("/api/v1/dashboard") ||
          url.startsWith("/api/v1/repositories")
        );
      }),
    ).toBe(true);
    expect(api.mock.calls.every(([path]) => !String(path).includes("/api/v1/public/"))).toBe(true);
  });

  it("serves repository pages through RepositoryDetails without the public repo shell", async () => {
    const user = userEvent.setup();
    page("/Alpha/project");
    expect(await screen.findByRole("button", { name: "Traffic" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Overview" })).toBeInTheDocument();
    expect(screen.queryByLabelText("Select repository")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("Switch repository")).not.toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "project" })).not.toBeInTheDocument();
    expect(screen.getByText("project")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Alpha" })).toHaveAttribute("href", "/Alpha");
    expect(
      api.mock.calls.every(([path]) => !String(path).includes("/admin/repositories?refresh")),
    ).toBe(true);
    await user.click(screen.getByRole("button", { name: "Overview" }));
    expect(
      await screen.findByRole("heading", { name: "About this repository" }),
    ).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Search Visibility" }));
    expect(await screen.findByRole("heading", { name: "Search Visibility" })).toBeInTheDocument();
    expect(await screen.findByRole("link", { name: searchHistory.query.name })).toHaveAttribute(
      "href",
      "/Alpha/project/search-queries/5",
    );
  });

  it("shows observed traffic totals on owner Partial data cards", () => {
    const stats: StatsResponse = {
      period: "30d",
      from: "2026-10-15",
      to: "2026-11-13",
      repositories: 10,
      totals,
    };
    const trafficResponse: TrafficResponse = {
      coverage: { observedRepositoryDays: 279, expectedRepositoryDays: 290 },
      previousCoverage: { observedRepositoryDays: 200, expectedRepositoryDays: 290 },
      totals: {
        views: {
          value: null,
          observedValue: 2547,
          complete: false,
          absoluteChange: null,
          growthPercent: null,
        },
        visitors: {
          value: null,
          observedValue: 446,
          complete: false,
          absoluteChange: null,
          growthPercent: null,
        },
        clones: {
          value: null,
          observedValue: 3965,
          complete: false,
          absoluteChange: null,
          growthPercent: null,
        },
        uniqueCloners: {
          value: null,
          observedValue: 1688,
          complete: false,
          absoluteChange: null,
          growthPercent: null,
        },
      },
    };
    const model = adaptPublicDashboard(overview, stats, [], trafficResponse, [], []);
    expect(model.summary.views.value).toBe(2547);
    expect(model.summary.visitors.value).toBe(446);
    expect(model.summary.uniqueCloners.value).toBe(1688);
    expect(model.summary.views.coverage).toEqual({ observedDays: 279, expectedDays: 290 });
    expect(model.summary.views.previousCoverage).toEqual({ observedDays: 200, expectedDays: 290 });
  });

  it("shows observed unique contributors when tip login sets are incomplete", () => {
    const stats: StatsResponse = {
      period: "30d",
      from: "2026-10-15",
      to: "2026-11-13",
      repositories: 10,
      totals: {
        ...totals,
        contributors: {
          value: null,
          observedValue: 14,
          complete: false,
          absoluteChange: null,
          growthPercent: null,
        },
      },
    };
    const model = adaptPublicDashboard(overview, stats, [], null, [], []);
    expect(model.stats.totals.contributors.value).toBe(14);
  });

  it("keeps complete public metrics, repository scope and history pagination edges", async () => {
    expect(publicBase("1", "9")).toBe("/api/v1/repositories/9");
    expect(publicBase("1")).toBe("/api/v1/owners/1");
    const repoOverview = {
      ...overview,
      repository: { id: "9", name: "project", pathPrefixes: ["/Alpha/project"] },
    };
    expect(publicScope(repoOverview)).toBe("public:repository:9");
    const stats: StatsResponse = {
      period: "30d",
      from: "2026-10-15",
      to: "2026-11-13",
      repositories: 1,
      totals: {
        ...totals,
        stars: { value: null, absoluteChange: null, growthPercent: null, complete: false },
        contributors: {
          value: 7,
          observedValue: 7,
          complete: true,
          absoluteChange: null,
          growthPercent: null,
        },
      },
      highlights: { rankRise: null, rankDrop: null, events: null, visitors: null },
    };
    const trafficResponse: TrafficResponse = {
      totals: {
        views: {
          value: 12,
          observedValue: 12,
          complete: true,
          absoluteChange: 1,
          growthPercent: 10,
        },
        visitors: {
          value: 0,
          observedValue: 0,
          complete: true,
          absoluteChange: 0,
          growthPercent: null,
        },
        clones: {
          value: 1,
          observedValue: 1,
          complete: true,
          absoluteChange: null,
          growthPercent: null,
        },
        uniqueCloners: {
          value: 1,
          observedValue: 1,
          complete: true,
          absoluteChange: null,
          growthPercent: null,
        },
      },
    };
    const model = adaptPublicDashboard(
      repoOverview,
      stats,
      [{ date: "2026-10-16", stars: null, forks: 2, watchers: null, contributors: 3 }],
      trafficResponse,
      [{ date: "2026-10-16", views: null, visitors: 2, clones: null, uniqueCloners: 1 }],
      [
        {
          id: "9",
          name: "project",
          archived: true,
          activityAt: "2026-10-16",
          activityStatus: "ACTIVE",
          discoverabilityPassed: 1,
          discoverabilityTotal: 2,
          growthPercent: 5,
          stars: 1,
          forks: 2,
          views: 3,
          visitors: 4,
          clones: 5,
        },
      ],
    );
    expect(model.summary.stars.total).toBe(0);
    expect(model.summary.views.value).toBe(12);
    expect(model.summary.views.coverage).toBeNull();
    expect(model.stats.totals.contributors.value).toBe(7);
    expect(model.stats.points[0]).toMatchObject({
      stars: null,
      forks: 2,
      watchers: null,
      contributors: 3,
    });
    expect(model.traffic[0]).toMatchObject({
      views: null,
      visitors: 2,
      clones: null,
      uniqueCloners: 1,
    });
    expect(model.repositories[0]).toMatchObject({
      archived: true,
      activityStatus: "ACTIVE",
      discoverabilityPassed: 1,
      discoverabilityTotal: 2,
      growthPercent: 5,
    });
    expect(relativeRepositoryPath("/Alpha/project", ["/Alpha/project"])).toBe("/");
    expect(relativeRepositoryPath("Alpha/project/issues", ["Alpha/project"])).toBe("/issues");
    api.mockResolvedValueOnce([
      {
        id: "e1",
        eventAt: "2026-10-16T00:00:00Z",
        title: "Ship",
        type: "RELEASE",
        category: "PROMOTION",
        source: "manual",
      },
    ]);
    expect(await publicEvents("/api/v1/owners/1", new AbortController().signal)).toEqual([
      {
        id: "e1",
        repositoryId: "",
        eventAt: "2026-10-16T00:00:00Z",
        title: "Ship",
        type: "RELEASE",
        category: "PROMOTION",
        source: "manual",
      },
    ]);
    api
      .mockResolvedValueOnce({ items: [{ date: "2026-10-16" }], nextDate: "2026-10-16" })
      .mockResolvedValueOnce({ items: [{ date: "2026-10-15" }] });
    expect(
      await historyRows(
        "/api/v1/owners/1/stats-history?period=30d",
        new AbortController().signal,
      ),
    ).toEqual([{ date: "2026-10-15" }, { date: "2026-10-16" }]);
    api.mockImplementation(() =>
      Promise.resolve({ items: [{ date: String(Math.random()) }], nextDate: "2026-10-01" }),
    );
    await expect(
      historyRows("/api/v1/owners/1/stats-history?period=all", new AbortController().signal),
    ).rejects.toThrow(/too large/);
    api.mockImplementation(() =>
      Promise.resolve({ items: [{ id: String(Math.random()), name: "r" }], nextCursor: "1" }),
    );
    await expect(
      publicRepositoryRows("/api/v1/owners/1", "30d", new AbortController().signal),
    ).rejects.toThrow(/too many repositories/);
  });

  it("reports errors and retries rather than leaking a different dashboard", async () => {
    let fail = true;
    api.mockImplementation((path: string) => {
      const url = String(path);
      if (fail && url.startsWith("/api/v1/owners?login=")) {
        return Promise.reject(Error("Unavailable"));
      }
      return Promise.resolve(respond(url));
    });
    const user = userEvent.setup();
    page();
    expect(await screen.findByRole("alert")).toHaveTextContent("Unavailable");
    fail = false;
    await user.click(screen.getByRole("button", { name: "Retry" }));
    expect(await screen.findByRole("navigation", { name: "Breadcrumb" })).toBeInTheDocument();
  });

  it("maps non-Error overview failures into Data unavailable", async () => {
    api.mockImplementation((path: string) => {
      if (String(path).startsWith("/api/v1/owners?login=")) {
        // eslint-disable-next-line @typescript-eslint/prefer-promise-reject-errors -- non-Error path
        return Promise.reject({ reason: "down" });
      }
      return Promise.resolve(respond(path));
    });
    const view = page();
    expect(await screen.findByRole("alert")).toHaveTextContent("[object Object]");
    view.unmount();
  });

  it("renders PublicNotFound for overview 404 responses", async () => {
    const { ApiError } = await import("../lib/api");
    api.mockImplementation((path: string) => {
      if (String(path).startsWith("/api/v1/owners?login=")) {
        return Promise.resolve(emptyCursorPage);
      }
      return Promise.resolve(respond(path));
    });
    const view = page("/Alpha?tab=stats");
    expect(await screen.findByRole("heading", { name: "Page not found" })).toBeInTheDocument();
    void ApiError;
    view.unmount();
  });

  it("covers dashboard-load failures with and without Error", async () => {
    api.mockImplementation((path: string) => {
      const url = String(path);
      if (url.startsWith("/api/v1/owners?login=") || url === "/api/v1/owners/1") {
        return Promise.resolve(respond(url));
      }
      if (url.includes("/api/v1/dashboard?")) {
        // eslint-disable-next-line @typescript-eslint/prefer-promise-reject-errors -- non-Error path
        return Promise.reject({ code: "stats-down" });
      }
      return Promise.resolve(respond(url));
    });
    const nonError = page("/Alpha?tab=stats&period=7d");
    expect(await screen.findByRole("alert")).toHaveTextContent("[object Object]");
    nonError.unmount();

    api.mockImplementation((path: string) => {
      const url = String(path);
      if (url.startsWith("/api/v1/owners?login=") || url === "/api/v1/owners/1") {
        return Promise.resolve(respond(url));
      }
      if (url.includes("/api/v1/dashboard?")) {
        return Promise.reject(new Error("stats boom"));
      }
      return Promise.resolve(respond(url));
    });
    const asError = page("/Alpha?tab=stats");
    expect(await screen.findByRole("alert")).toHaveTextContent("stats boom");
    asError.unmount();
  });

  it("updates period and stats tab from the public owner dashboard", async () => {
    const user = userEvent.setup();
    const view = page("/Alpha");
    expect(await screen.findByRole("navigation", { name: "Breadcrumb" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Stats" }));
    await user.click(await screen.findByRole("button", { name: "7d" }));
    expect(await screen.findByRole("button", { name: "7d" })).toHaveAttribute(
      "aria-pressed",
      "true",
    );
    expect(api.mock.calls.some(([path]) => String(path).includes("period=7d"))).toBe(true);
    expect(api.mock.calls.some(([path]) => String(path).includes("/api/v1/dashboard?"))).toBe(true);
    view.unmount();
  });

  it("follows canonical owner path redirects", async () => {
    api.mockImplementation((path: string) => {
      const url = String(path);
      if (url.startsWith("/api/v1/owners?login=Alpha")) {
        return Promise.resolve({ items: [{ ownerId: "1", login: "Alpha" }], nextCursor: null });
      }
      if (url === "/api/v1/owners/1") {
        return Promise.resolve({ ownerId: "1", login: "Beta", ownerType: "USER" });
      }
      if (url.startsWith("/api/v1/owners?login=Beta")) {
        return Promise.resolve({ items: [{ ownerId: "1", login: "Beta" }], nextCursor: null });
      }
      if (url.includes("/api/v1/dashboard?")) {
        return Promise.resolve({
          ...alphaDashboard,
          repositories: [{ ...alphaDashboard.repositories[0], fullName: "Beta/project" }],
        });
      }
      if (url.includes("/growth-events")) return Promise.resolve([]);
      return Promise.resolve(respond(url));
    });
    const view = page("/Alpha");
    expect(await screen.findByRole("navigation", { name: "Breadcrumb" })).toHaveTextContent("Beta");
    view.unmount();
  });

  it("shows empty states without operational tracking signals", async () => {
    api.mockImplementation((path: string) => {
      const url = String(path);
      if (url.includes("/api/v1/dashboard?")) {
        return Promise.resolve({
          ...alphaDashboard,
          lastSyncAt: null,
          state: "NO_REPOSITORIES",
          repositories: [],
        });
      }
      return Promise.resolve(respond(url));
    });
    page();
    expect(await screen.findByText(/No saved snapshots yet/)).toBeInTheDocument();
  });

  it("isolates stable owner/repository preferences without growing cookies", () => {
    const cookies = document.cookie;
    writePublicPreference("public:owner:1", "period", "7d");
    writePublicPreference("public:owner:2", "period", "90d");
    expect(
      readPublicPreference(
        publicScope(overview),
        "period",
        (v): v is string => typeof v === "string",
      ),
    ).toBe("7d");
    expect(
      readPublicPreference(
        "public:repository:1",
        "period",
        (v): v is string => typeof v === "string",
      ),
    ).toBeUndefined();
    for (let i = 0; i < 400; i++) writePublicPreference(`public:owner:${i}`, "sort", "stars");
    const storedPrefs: unknown = JSON.parse(localStorage.getItem("rgm-public-preferences-v1")!);
    expect(Object.keys(storedPrefs as Record<string, unknown>)).toHaveLength(256);
    expect(document.cookie).toBe(cookies);
  });

  it("matches only complete repository prefixes and retains cross-owner names", () => {
    expect(relativeRepositoryPath("/Alpha/project/issues", ["/Alpha/project"])).toBe("/issues");
    expect(relativeRepositoryPath("/Alpha/project-other/issues", ["/Alpha/project"])).toBe(
      "/Alpha/project-other/issues",
    );
    expect(relativeRepositoryPath("/Old/project?tab=readme", ["/Old/project"])).toBe(
      "/?tab=readme",
    );
  });

  it("changes chart labels and exports without changing raw path identity or missing values", () => {
    const history: SnapshotHistory = {
      repositoryId: "public:repository:9007199254740993",
      kind: "PATHS",
      days: 2,
      from: "2026-11-01",
      to: "2026-11-02",
      dates: ["2026-11-01", "2026-11-02"],
      rows: [
        {
          key: "/Alpha/project/issues",
          title: "/issues",
          cells: [
            {
              date: "2026-11-01",
              visitors: 0,
              views: 0,
              visitorsDelta: null,
              viewsDelta: null,
              firstSeen: true,
            },
            {
              date: "2026-11-02",
              visitors: null,
              views: null,
              visitorsDelta: null,
              viewsDelta: null,
              firstSeen: false,
            },
          ],
        },
      ],
    };
    const option = snapshotChartOption({
      history,
      metric: "VISITORS",
      selected: [history.rows[0].key],
      displayLabel: (key) => relativeRepositoryPath(key, ["/Alpha/project"]),
    });
    expect(option.series[0].name).toBe("/Alpha/project/issues");
    expect(option.series[0].data).toEqual([0, null]);
    expect(snapshotSeriesKey(history, history.rows[0].key)).toContain("9007199254740993");
    expect(chartCsv(option)).toContain("/issues - Visitors");
    expect(chartCsv(option)).not.toContain("/Alpha/project");
  });

  it("keeps unknown measurements null and actual zeros intact", () => {
    const stats: StatsResponse = {
      period: "30d",
      from: "2026-11-01",
      to: "2026-11-02",
      repositories: 1,
      totals,
    };
    const model = adaptPublicDashboard(
      overview,
      stats,
      [],
      null,
      [{ date: "2026-11-01", views: 0, visitors: null }],
      [{ id: "9007199254740993", name: "project" }],
    );
    expect(model.repositories[0].id).toBe("9007199254740993");
    expect(model.repositories[0].stars).toBeNull();
    expect(model.traffic[0].views).toBe(0);
    expect(model.traffic[0].visitors).toBeNull();
  });

  it("keeps stats-history incompleteness for tooltips", () => {
    const stats: StatsResponse = {
      period: "30d",
      from: "2026-10-01",
      to: "2026-10-30",
      repositories: 10,
      totals,
    };
    const model = adaptPublicDashboard(
      overview,
      stats,
      [
        {
          date: "2026-10-30",
          stars: 48,
          forks: 22,
          watchers: 2,
          contributors: null,
          missingRepositories: 1,
          complete: false,
        },
      ],
      null,
      [],
      [],
    );
    expect(model.stats.points[0]).toMatchObject({
      date: "2026-10-30",
      stars: 48,
      missingRepositories: 1,
    });
  });

  it("passes through traffic and stats leaders for chart tooltips", () => {
    const stats: StatsResponse = {
      period: "7d",
      from: "2026-11-12",
      to: "2026-11-18",
      repositories: 2,
      totals,
      highlights: {
        rankRise: {
          repositoryId: "11",
          fullName: "KHolodilin/spring-boot-outbox-starter",
          queryName: "outbox language:java",
          fromRank: 45,
          toRank: 31,
        },
        rankDrop: null,
        events: null,
        visitors: null,
      },
    };
    const model = adaptPublicDashboard(
      overview,
      stats,
      [
        {
          date: "2026-11-16",
          stars: 50,
          forks: 10,
          watchers: 1,
          contributors: 4,
          starsLeaders: [{ fullName: "KHolodilin/a", value: 40 }],
        },
      ],
      null,
      [
        {
          date: "2026-11-16",
          views: 10,
          visitors: 9,
          clones: 1,
          uniqueCloners: 1,
          visitorsLeaders: [
            { fullName: "KHolodilin/a", value: 5 },
            { fullName: "KHolodilin/b", value: 4 },
          ],
        },
      ],
      [],
    );
    expect(model.highlights?.rankRise?.fromRank).toBe(45);
    expect(model.traffic[0].visitorsLeaders).toEqual([
      { fullName: "KHolodilin/a", value: 5 },
      { fullName: "KHolodilin/b", value: 4 },
    ]);
    expect(model.stats.points[0].starsLeaders).toEqual([{ fullName: "KHolodilin/a", value: 40 }]);
  });

  it("accepts paged growth-event payloads from resource owners", async () => {
    api.mockResolvedValueOnce({
      items: [
        {
          id: "e2",
          repositoryId: "13",
          eventAt: "2026-10-16T00:00:00Z",
          title: "Ship",
          type: "RELEASE",
          category: "PROMOTION",
          source: "manual",
        },
      ],
    });
    expect(await publicEvents("/api/v1/owners/1", new AbortController().signal)).toEqual([
      {
        id: "e2",
        repositoryId: "13",
        eventAt: "2026-10-16T00:00:00Z",
        title: "Ship",
        type: "RELEASE",
        category: "PROMOTION",
        source: "manual",
      },
    ]);
  });

  it("keeps the dashboard when growth-events fail for an owner", async () => {
    api.mockImplementation((path: string) => {
      const url = String(path);
      if (url.includes("/growth-events")) {
        return Promise.reject(new Error("events down"));
      }
      return Promise.resolve(respond(url));
    });
    page("/Alpha");
    expect(await screen.findByRole("navigation", { name: "Breadcrumb" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Alpha/project" })).toBeInTheDocument();
  });

  it("loads subsequent owner pages without truncating the repository list", async () => {
    api
      .mockResolvedValueOnce({
        items: [{ repositoryId: "1", name: "first" }],
        nextCursor: "1",
      })
      .mockResolvedValueOnce({ items: [{ id: "2", name: "second" }] });
    expect(
      await publicRepositoryRows("/api/v1/owners/1", "7d", new AbortController().signal),
    ).toEqual([
      expect.objectContaining({ id: "1", name: "first" }),
      expect.objectContaining({ id: "2", name: "second" }),
    ]);
    expect(api.mock.calls[1][0]).toContain("cursor=1");
  });

  it("builds repository hrefs from dashboard full names", async () => {
    const user = userEvent.setup();
    page("/Alpha");
    const link = await screen.findByRole("link", { name: "Alpha/project" });
    expect(link).toHaveAttribute("href", "/Alpha/project");
    await user.click(link);
    expect(await screen.findByRole("button", { name: "Traffic" })).toBeInTheDocument();
  });

  it("does not invent planner warnings from paused public archives", () => {
    const option = {
      collectionExpected: false,
      collectionDates: ["2026-11-01"],
      xAxis: { type: "category", data: ["2026-11-01", "2026-11-02"] },
      series: [{ name: "Visitors", type: "line", data: [0, null] }],
    };
    expect(withCollectionWarning(option, "2026-11-03")).toEqual(option);
  });

  it("leaves unknown browser routes as not found", async () => {
    page("/Alpha/project/unavailable");
    expect(await screen.findByRole("heading", { name: "Page not found" })).toBeInTheDocument();
    expect(api.mock.calls.some(([path]) => String(path).includes("/stats"))).toBe(false);
  });
});
