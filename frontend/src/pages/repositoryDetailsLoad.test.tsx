import { act, cleanup, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { growthEvent, repository, searchHistory, traffic } from "../test/fixtures";
import { isPublicResolve, publicResolveResponse } from "../test/repoRoutes";
import { RepositoryDetailsPage } from "./RepositoryDetailsPage";

const { api } = vi.hoisted(() => ({ api: vi.fn() }));
vi.mock("../lib/api", async (original) => ({
  ...(await original<typeof import("../lib/api")>()),
  api,
}));

function renderDetails(path = "/admin/repositories/13") {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/admin/repositories/:id" element={<RepositoryDetailsPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

function mockDetailsApi(handlers: { repository?: () => Promise<unknown> }) {
  api.mockImplementation((path: unknown) => {
    const url = String(path);
    if (isPublicResolve(url)) return Promise.resolve(publicResolveResponse());
    if (url.endsWith("/admin/repositories/13")) {
      return handlers.repository ? handlers.repository() : Promise.resolve(repository);
    }
    if (url.includes("/admin/repositories?") || url.endsWith("/admin/repositories")) {
      return Promise.resolve([repository]);
    }
    if (url.includes("/traffic?")) return Promise.resolve(traffic);
    if (url.includes("search-queries")) return Promise.resolve([searchHistory]);
    if (url.includes("topic-watches")) return Promise.resolve([]);
    if (url.includes("growth-events")) return Promise.resolve([]);
    if (url.includes("growth-event-settings")) return Promise.resolve([]);
    return Promise.resolve([]);
  });
}

afterEach(() => cleanup());
beforeEach(() => api.mockReset());

describe("RepositoryDetailsPage load races", () => {
  it("clears a previous error when a newer load succeeds", async () => {
    api.mockImplementation((path: unknown) => {
      const url = String(path);
      if (isPublicResolve(url)) return Promise.resolve(publicResolveResponse());
      if (url.endsWith("/admin/repositories/13")) {
        return Promise.reject(new Error("temporary outage"));
      }
      if (url.includes("/admin/repositories?") || url.endsWith("/admin/repositories")) {
        return Promise.resolve([repository]);
      }
      if (url.includes("/traffic?")) return Promise.resolve(traffic);
      if (url.includes("search-queries")) return Promise.resolve([searchHistory]);
      if (url.includes("topic-watches")) return Promise.resolve([]);
      if (url.includes("growth-events")) return Promise.resolve([]);
      return Promise.resolve([]);
    });

    renderDetails();
    expect(await screen.findByText("temporary outage")).toBeInTheDocument();

    mockDetailsApi({});
    await act(async () => {
      window.dispatchEvent(new Event("repos-collected"));
      await Promise.resolve();
    });
    expect(await screen.findByRole("link", { name: "GitHub Traffic" })).toBeInTheDocument();
    expect(screen.queryByText("temporary outage")).not.toBeInTheDocument();
  });

  it("ignores a stale rejection after a newer request has started", async () => {
    let failStale: (reason: unknown) => void = () => undefined;
    let finishFresh: (value: unknown) => void = () => undefined;
    let repoCalls = 0;
    mockDetailsApi({
      repository: () => {
        repoCalls += 1;
        if (repoCalls === 1) {
          return new Promise((_, reject) => {
            failStale = reject;
          });
        }
        return new Promise((resolve) => {
          finishFresh = resolve;
        });
      },
    });

    renderDetails();
    await waitFor(() => expect(repoCalls).toBe(1));
    await act(async () => {
      window.dispatchEvent(new Event("repos-collected"));
      await Promise.resolve();
    });
    await waitFor(() => expect(repoCalls).toBe(2));

    failStale(new Error("stale failure"));
    await act(async () => {
      await Promise.resolve();
    });
    expect(screen.queryByText("stale failure")).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "GitHub Traffic" })).not.toBeInTheDocument();

    finishFresh(repository);
    expect(await screen.findByRole("link", { name: "GitHub Traffic" })).toBeInTheDocument();
    expect(screen.queryByText("stale failure")).not.toBeInTheDocument();
  });

  it("aborts the in-flight repos-collected request on unmount", async () => {
    const signals: AbortSignal[] = [];
    let repoCalls = 0;
    api.mockImplementation((path: unknown, init?: RequestInit) => {
      const url = String(path);
      if (init?.signal) {
        signals.push(init.signal);
      }
      if (isPublicResolve(url)) return Promise.resolve(publicResolveResponse());
      if (url.endsWith("/admin/repositories/13")) {
        repoCalls += 1;
        if (repoCalls === 1) {
          return Promise.resolve(repository);
        }
        return new Promise(() => undefined);
      }
      if (url.includes("/admin/repositories?") || url.endsWith("/admin/repositories")) {
        return Promise.resolve([repository]);
      }
      if (url.includes("/traffic?")) return Promise.resolve(traffic);
      if (url.includes("search-queries")) return Promise.resolve([searchHistory]);
      if (url.includes("topic-watches")) return Promise.resolve([]);
      if (url.includes("growth-events")) return Promise.resolve([]);
      return Promise.resolve([]);
    });

    const view = renderDetails();
    expect(await screen.findByRole("link", { name: "GitHub Traffic" })).toBeInTheDocument();
    const beforeRefresh = signals.length;
    await act(async () => {
      window.dispatchEvent(new Event("repos-collected"));
      await Promise.resolve();
    });
    await waitFor(() => expect(signals.length).toBeGreaterThan(beforeRefresh));
    const refreshSignal = signals[signals.length - 1];
    expect(refreshSignal?.aborted).toBe(false);
    view.unmount();
    expect(refreshSignal?.aborted).toBe(true);
  });

  it("keeps repository traffic visible when growth events fail", async () => {
    api.mockImplementation((path: unknown) => {
      const url = String(path);
      if (isPublicResolve(url)) return Promise.resolve(publicResolveResponse());
      if (url.endsWith("/admin/repositories/13")) return Promise.resolve(repository);
      if (url.includes("/admin/repositories?") || url.endsWith("/admin/repositories")) {
        return Promise.resolve([repository]);
      }
      if (url.includes("/traffic?")) return Promise.resolve(traffic);
      if (url.includes("search-queries")) return Promise.resolve([searchHistory]);
      if (url.includes("topic-watches")) return Promise.resolve([]);
      if (url.includes("growth-events") && !url.includes("settings")) {
        return Promise.reject(new Error("events offline"));
      }
      return Promise.resolve([]);
    });

    renderDetails("/admin/repositories/13?tab=growth-events");
    expect(await screen.findByText("events offline")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Try again" })).toBeInTheDocument();
    expect(screen.queryByText(/No growth events/)).not.toBeInTheDocument();

    await act(async () => {
      screen.getByRole("button", { name: "Traffic" }).click();
      await Promise.resolve();
    });
    expect(await screen.findByRole("link", { name: "GitHub Traffic" })).toBeInTheDocument();
    expect(await screen.findByText("events offline")).toBeInTheDocument();
    expect(screen.queryByText(/No growth events/)).not.toBeInTheDocument();
  });

  it("shows the empty growth-events state only after a successful empty response", async () => {
    mockDetailsApi({});
    renderDetails("/admin/repositories/13?tab=growth-events");
    expect(await screen.findByText(/No growth events in this period/)).toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(screen.queryByText(/Loading growth events/)).not.toBeInTheDocument();
  });

  it("recovers growth events after Try again clears a prior error", async () => {
    let eventCalls = 0;
    api.mockImplementation((path: unknown) => {
      const url = String(path);
      if (isPublicResolve(url)) return Promise.resolve(publicResolveResponse());
      if (url.endsWith("/admin/repositories/13")) return Promise.resolve(repository);
      if (url.includes("/admin/repositories?") || url.endsWith("/admin/repositories")) {
        return Promise.resolve([repository]);
      }
      if (url.includes("/traffic?")) return Promise.resolve(traffic);
      if (url.includes("search-queries")) return Promise.resolve([searchHistory]);
      if (url.includes("topic-watches")) return Promise.resolve([]);
      if (url.includes("growth-events") && !url.includes("settings")) {
        eventCalls += 1;
        if (eventCalls === 1) {
          return Promise.reject(new Error("events offline"));
        }
        return Promise.resolve([{ ...growthEvent, title: "Recovered launch" }]);
      }
      return Promise.resolve([]);
    });

    renderDetails("/admin/repositories/13?tab=growth-events");
    expect(await screen.findByText("events offline")).toBeInTheDocument();
    await act(async () => {
      screen.getByRole("button", { name: "Try again" }).click();
      await Promise.resolve();
    });
    expect(await screen.findByText("Recovered launch")).toBeInTheDocument();
    expect(screen.queryByText("events offline")).not.toBeInTheDocument();
  });
});
