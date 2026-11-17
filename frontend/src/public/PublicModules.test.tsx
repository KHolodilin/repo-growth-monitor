import { cleanup, render, screen, waitFor } from "@testing-library/react";
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
      .mockResolvedValueOnce({ items: [{ id: "1", label: "github.com", views: 2, visitors: 1, observedAt: "2026-11-01T00:00:00Z" }] })
      .mockResolvedValueOnce({ items: [{ id: "2", label: "/Alpha/project/issues", views: 3, visitors: 1 }] });
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
    api.mockResolvedValueOnce({ items: [{ id: "2", label: "/Alpha/project/readme", views: 1, visitors: 1 }] });
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
});
