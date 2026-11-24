import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { GrowthEvent } from "../lib/api";
import type { PublicOverview, PublicRow } from "../lib/publicDashboard";
import { PublicModules } from "./PublicModules";

const { api } = vi.hoisted(() => ({ api: vi.fn() }));
vi.mock("../lib/api", async (original) => ({
  ...(await original<typeof import("../lib/api")>()),
  api,
}));

const overview: PublicOverview = {
  owner: { id: "1", login: "Alpha", type: "USER" },
  canonicalPath: "/Alpha/project",
  repositoryCount: 1,
  modules: { traffic: true, referrers: true, paths: true, search: true, topics: true },
  freshness: { state: "READY" },
  repository: { id: "13", name: "project", pathPrefixes: ["/Alpha/project"] },
};
const rows: PublicRow[] = [{ id: "13", name: "project", stars: 1, forks: 0 }];
const sampleEvent: GrowthEvent = {
  id: "e1",
  repositoryId: 13,
  eventAt: "2026-11-01T00:00:00Z",
  category: "GROWTH",
  type: "STARS",
  title: "First star",
  source: "SYSTEM",
};

function mount(tab: string, events: GrowthEvent[] = [sampleEvent]) {
  cleanup();
  return render(
    <MemoryRouter>
      <PublicModules overview={overview} rows={rows} tab={tab} period="7d" events={events} />
    </MemoryRouter>,
  );
}

beforeEach(() => {
  api.mockReset();
});
afterEach(() => cleanup());

describe("PublicModules", () => {
  it("lists published search queries and loads more pages", async () => {
    const user = userEvent.setup();
    api
      .mockResolvedValueOnce({
        items: [{ id: "5", repositoryId: "13", name: "outbox", query: "outbox language:Java" }],
        nextCursor: "cursor-1",
      })
      .mockResolvedValueOnce({
        items: [{ id: "6", repositoryId: "13", name: "kafka", query: "kafka" }],
      });
    mount("search");
    expect(await screen.findByRole("heading", { name: "Search Visibility" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "outbox" })).toHaveAttribute(
      "href",
      "/Alpha/project/analytics/search/5?period=7d",
    );
    await user.click(screen.getByRole("button", { name: "Load more" }));
    expect(await screen.findByRole("link", { name: "kafka" })).toBeInTheDocument();
    expect(api.mock.calls[1][0]).toContain("after=cursor-1");
  });

  it("renders topics empty list, search errors and unavailable tabs", async () => {
    api.mockResolvedValueOnce({ items: [] });
    mount("topics");
    expect(await screen.findByText(/No published topic watches/)).toBeInTheDocument();

    api.mockRejectedValueOnce(new Error("offline"));
    mount("search");
    expect(await screen.findByRole("alert")).toHaveTextContent("offline");

    mount("unknown");
    expect(screen.getByText("This section is not available.")).toBeInTheDocument();
  });

  it("shows events and source cards for referrers/paths", async () => {
    mount("events");
    expect(screen.getByRole("heading", { name: "Recent Growth Events" })).toBeInTheDocument();
    expect(screen.getByText("First star")).toBeInTheDocument();

    api
      .mockResolvedValueOnce({
        items: [
          {
            id: "1",
            label: "github.com",
            views: 2,
            visitors: 1,
            observedAt: "2026-11-01T00:00:00Z",
          },
        ],
      })
      .mockResolvedValueOnce({
        items: [{ id: "2", label: "/Alpha/project/issues", views: 3, visitors: 1 }],
      });
    mount("sources");
    expect(await screen.findByText("Top Referrers")).toBeInTheDocument();
    expect(screen.getByText("Popular Paths")).toBeInTheDocument();
    await waitFor(() => expect(screen.getByText("github.com")).toBeInTheDocument());
  });

  it("keeps empty event and source error states honest", async () => {
    mount("events", []);
    expect(screen.getByRole("status")).toHaveTextContent(/No growth events/);

    api.mockRejectedValueOnce(new Error("sources down"));
    mount("sources");
    expect(await screen.findByRole("alert")).toHaveTextContent("sources down");
  });

  it("labels topic languages, skips orphan rows, and respects module flags", async () => {
    const ownerOnly = { ...overview, repository: undefined };
    api.mockResolvedValueOnce({
      items: [
        { id: "7", repositoryId: "13", topic: "outbox", language: "Java", resultLimit: 50 },
        { id: "8", repositoryId: "999", topic: "orphan" },
      ],
    });
    cleanup();
    render(
      <MemoryRouter>
        <PublicModules overview={ownerOnly} rows={rows} tab="topics" period="7d" events={[]} />
      </MemoryRouter>,
    );
    expect(await screen.findByRole("link", { name: /outbox · Java/ })).toBeInTheDocument();
    expect(screen.getByText("Top 50")).toBeInTheDocument();
    expect(screen.queryByText("orphan")).not.toBeInTheDocument();

    const pathsOnly = {
      ...overview,
      modules: { ...overview.modules, referrers: false, paths: true },
    };
    api.mockResolvedValueOnce({
      items: [{ id: "2", label: "/Alpha/project/readme", views: 1, visitors: 1 }],
    });
    cleanup();
    render(
      <MemoryRouter>
        <PublicModules overview={pathsOnly} rows={rows} tab="sources" period="1d" events={[]} />
      </MemoryRouter>,
    );
    expect(await screen.findByText("Popular Paths")).toBeInTheDocument();
    expect(screen.queryByText("Top Referrers")).not.toBeInTheDocument();

    const referrersOnly = {
      ...overview,
      modules: { ...overview.modules, referrers: true, paths: false },
    };
    api.mockResolvedValueOnce({ items: [] });
    cleanup();
    render(
      <MemoryRouter>
        <PublicModules overview={referrersOnly} rows={rows} tab="sources" period="7d" events={[]} />
      </MemoryRouter>,
    );
    expect(await screen.findByText("Top Referrers")).toBeInTheDocument();
    expect(screen.queryByText("Popular Paths")).not.toBeInTheDocument();

    const neither = {
      ...overview,
      modules: { ...overview.modules, referrers: false, paths: false },
    };
    cleanup();
    render(
      <MemoryRouter>
        <PublicModules overview={neither} rows={rows} tab="sources" period="7d" events={[]} />
      </MemoryRouter>,
    );
    await waitFor(() => expect(screen.queryByRole("status")).not.toBeInTheDocument());
    expect(screen.queryByText("Top Referrers")).not.toBeInTheDocument();
    expect(screen.queryByText("Popular Paths")).not.toBeInTheDocument();
  });

  it("reports Load more failures for Error and non-Error rejections", async () => {
    const user = userEvent.setup();
    api
      .mockResolvedValueOnce({
        items: [{ id: "5", repositoryId: "13", name: "outbox" }],
        nextCursor: "cursor-err",
      })
      .mockRejectedValueOnce(new Error("page failed"))
      .mockResolvedValueOnce({
        items: [{ id: "8", repositoryId: "13", name: "recovered" }],
      })
      .mockResolvedValueOnce({
        items: [{ id: "7", repositoryId: "13", name: "retry" }],
        nextCursor: "cursor-str",
      })
      .mockRejectedValueOnce("string fail");
    mount("search");
    expect(await screen.findByRole("link", { name: "outbox" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Load more" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("page failed");
    expect(screen.getByRole("link", { name: "outbox" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Load more" }));
    expect(await screen.findByRole("link", { name: "recovered" })).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();

    mount("search");
    expect(await screen.findByRole("link", { name: "retry" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Load more" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("string fail");
    expect(screen.getByRole("link", { name: "retry" })).toBeInTheDocument();
  });

  it("ignores a second Load more click while the first page request is in flight", async () => {
    let finishMore: (value: unknown) => void = () => undefined;
    api
      .mockResolvedValueOnce({
        items: [{ id: "5", repositoryId: "13", name: "outbox" }],
        nextCursor: "cursor-1",
      })
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            finishMore = resolve;
          }),
      );
    mount("search");
    expect(await screen.findByRole("button", { name: "Load more" })).toBeInTheDocument();
    const button = screen.getByRole("button", { name: "Load more" });
    // Synchronous double-click before React re-renders the disabled loading state.
    fireEvent.click(button);
    fireEvent.click(button);
    expect(screen.getByRole("button", { name: "Loading…" })).toBeDisabled();
    expect(api.mock.calls.filter(([path]) => String(path).includes("after="))).toHaveLength(1);
    finishMore({ items: [{ id: "6", repositoryId: "13", name: "kafka" }], nextCursor: null });
    expect(await screen.findByRole("link", { name: "kafka" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Load more" })).not.toBeInTheDocument();
  });

  it("allows retry after Load more error and does not duplicate in-flight pages", async () => {
    const user = userEvent.setup();
    let finishMore: (value: unknown) => void = () => undefined;
    let moreCalls = 0;
    api.mockImplementation((path: string) => {
      const url = String(path);
      if (url.includes("/search-queries?") && !url.includes("after=")) {
        return Promise.resolve({
          items: [{ id: "5", repositoryId: "13", name: "outbox" }],
          nextCursor: "cursor-1",
        });
      }
      if (url.includes("after=cursor-1")) {
        moreCalls += 1;
        if (moreCalls === 1) return Promise.reject(new Error("page busy"));
        return new Promise((resolve) => {
          finishMore = resolve;
        });
      }
      return Promise.resolve({ items: [], nextCursor: null });
    });
    mount("search");
    expect(await screen.findByRole("button", { name: "Load more" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Load more" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("page busy");
    expect(screen.getByRole("link", { name: "outbox" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Load more" }));
    fireEvent.click(screen.getByRole("button", { name: "Loading…" }));
    expect(moreCalls).toBe(2);
    finishMore({ items: [{ id: "6", repositoryId: "13", name: "kafka" }], nextCursor: null });
    expect(await screen.findByRole("link", { name: "kafka" })).toBeInTheDocument();
  });

  it("drops a Load more response after the owner/tab context changes", async () => {
    const user = userEvent.setup();
    let finishMore: (value: unknown) => void = () => undefined;
    api.mockImplementation((path: string) => {
      const url = String(path);
      if (url.includes("/search-queries?") && !url.includes("after=")) {
        return Promise.resolve({
          items: [{ id: "5", repositoryId: "13", name: "outbox" }],
          nextCursor: "cursor-1",
        });
      }
      if (url.includes("after=cursor-1")) {
        return new Promise((resolve) => {
          finishMore = resolve;
        });
      }
      if (url.includes("/topics?")) {
        return Promise.resolve({
          items: [{ id: "9", repositoryId: "13", name: "outbox", topic: "outbox" }],
          nextCursor: null,
        });
      }
      return Promise.resolve({ items: [], nextCursor: null });
    });
    const { rerender } = render(
      <MemoryRouter>
        <PublicModules overview={overview} rows={rows} tab="search" period="7d" events={[]} />
      </MemoryRouter>,
    );
    expect(await screen.findByRole("link", { name: "outbox" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Load more" }));
    rerender(
      <MemoryRouter>
        <PublicModules overview={overview} rows={rows} tab="topics" period="7d" events={[]} />
      </MemoryRouter>,
    );
    expect(await screen.findByText("outbox")).toBeInTheDocument();
    finishMore({
      items: [{ id: "6", repositoryId: "13", name: "stale-page" }],
      nextCursor: "cursor-2",
    });
    await waitFor(() => {
      expect(screen.queryByText("stale-page")).not.toBeInTheDocument();
    });
  });

  it("ignores Load more rejection after the request was superseded", async () => {
    const user = userEvent.setup();
    let failMore: (reason: unknown) => void = () => undefined;
    api.mockImplementation((path: string) => {
      const url = String(path);
      if (url.includes("/search-queries?") && !url.includes("after=")) {
        return Promise.resolve({
          items: [{ id: "5", repositoryId: "13", name: "outbox" }],
          nextCursor: "cursor-1",
        });
      }
      if (url.includes("after=cursor-1")) {
        return new Promise((_, reject) => {
          failMore = reject;
        });
      }
      if (url.includes("/topics?")) {
        return Promise.resolve({
          items: [{ id: "9", repositoryId: "13", topic: "outbox" }],
          nextCursor: null,
        });
      }
      return Promise.resolve({ items: [], nextCursor: null });
    });
    const { rerender } = render(
      <MemoryRouter>
        <PublicModules overview={overview} rows={rows} tab="search" period="7d" events={[]} />
      </MemoryRouter>,
    );
    expect(await screen.findByRole("link", { name: "outbox" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Load more" }));
    rerender(
      <MemoryRouter>
        <PublicModules overview={overview} rows={rows} tab="topics" period="7d" events={[]} />
      </MemoryRouter>,
    );
    expect(await screen.findByText("outbox")).toBeInTheDocument();
    failMore(new Error("stale more failure"));
    await waitFor(() => {
      expect(screen.queryByText("stale more failure")).not.toBeInTheDocument();
    });
  });

  it("resets search definitions when switching tabs and reports non-Error failures", async () => {
    api.mockImplementation((path: string) => {
      if (String(path).includes("/search-queries")) {
        return Promise.resolve({
          items: [{ repositoryId: "13", id: "5", name: "outbox" }],
          nextCursor: null,
        });
      }
      if (String(path).includes("/topics")) {
        // eslint-disable-next-line @typescript-eslint/prefer-promise-reject-errors -- non-Error path
        return Promise.reject("topics down");
      }
      return Promise.resolve({ items: [], nextCursor: null });
    });
    const { rerender } = render(
      <MemoryRouter>
        <PublicModules overview={overview} rows={rows} tab="search" period="7d" events={[]} />
      </MemoryRouter>,
    );
    expect(await screen.findByText("outbox")).toBeInTheDocument();
    rerender(
      <MemoryRouter>
        <PublicModules overview={overview} rows={rows} tab="topics" period="7d" events={[]} />
      </MemoryRouter>,
    );
    expect(await screen.findByRole("alert")).toHaveTextContent("topics down");
  });

  it("resets source cards when module flags change and reports string errors", async () => {
    api.mockRejectedValue("sources down");
    const { rerender } = render(
      <MemoryRouter>
        <PublicModules overview={overview} rows={rows} tab="sources" period="7d" events={[]} />
      </MemoryRouter>,
    );
    expect(await screen.findByRole("alert")).toHaveTextContent("sources down");
    const pathsOnly = {
      ...overview,
      modules: { ...overview.modules, referrers: false, paths: true },
    };
    api.mockResolvedValue({
      items: [{ id: "p1", label: "/Alpha/project/readme", views: 1, visitors: 1 }],
    });
    rerender(
      <MemoryRouter>
        <PublicModules overview={pathsOnly} rows={rows} tab="sources" period="7d" events={[]} />
      </MemoryRouter>,
    );
    expect(await screen.findByText("Popular Paths")).toBeInTheDocument();
    expect(screen.queryByText("Top Referrers")).not.toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(api.mock.calls.some(([path]) => String(path).includes("/paths"))).toBe(true);
  });
});
