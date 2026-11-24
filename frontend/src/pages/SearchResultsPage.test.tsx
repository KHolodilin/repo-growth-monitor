import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes, useNavigate } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { searchResults } from "../test/fixtures";
import { SearchResultsPage } from "./SearchResultsPage";

const { api } = vi.hoisted(() => ({ api: vi.fn() }));
vi.mock("../lib/api", async (original) => ({
  ...(await original<typeof import("../lib/api")>()),
  api,
}));

function resultsFor(runId: number, queryLabel: string) {
  return {
    ...searchResults,
    run: { ...searchResults.run, id: runId },
    query: { ...searchResults.query, query: queryLabel },
  };
}

function NavHarness() {
  const navigate = useNavigate();
  return (
    <>
      <button type="button" onClick={() => void navigate("/admin/search-runs/1")}>
        open-1
      </button>
      <button type="button" onClick={() => void navigate("/admin/search-runs/2")}>
        open-2
      </button>
      <Routes>
        <Route path="/admin/search-runs/:id" element={<SearchResultsPage />} />
      </Routes>
    </>
  );
}

function renderPage(path = "/admin/search-runs/1") {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <NavHarness />
    </MemoryRouter>,
  );
}

afterEach(() => {
  cleanup();
});

beforeEach(() => {
  api.mockReset();
});

describe("SearchResultsPage request races", () => {
  it("keeps B when B finishes before A after navigating without remount", async () => {
    const user = userEvent.setup();
    let finishA: (value: unknown) => void = () => undefined;
    let finishB: (value: unknown) => void = () => undefined;
    api.mockImplementation((path: string) => {
      const url = String(path);
      if (url.includes("/search-runs/1/")) {
        return new Promise((resolve) => {
          finishA = resolve;
        });
      }
      if (url.includes("/search-runs/2/")) {
        return new Promise((resolve) => {
          finishB = resolve;
        });
      }
      return Promise.reject(new Error(`unexpected ${url}`));
    });

    renderPage("/admin/search-runs/1");
    expect(screen.getByText("Loading search results…")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "open-2" }));
    expect(screen.getByText("Loading search results…")).toBeInTheDocument();

    finishB(resultsFor(2, "query-b"));
    expect(await screen.findByRole("heading", { name: "query-b" })).toBeInTheDocument();

    finishA(resultsFor(1, "query-a"));
    await waitFor(() => {
      expect(screen.getByRole("heading", { name: "query-b" })).toBeInTheDocument();
    });
    expect(screen.queryByRole("heading", { name: "query-a" })).not.toBeInTheDocument();
  });

  it("ignores error A after successful B", async () => {
    const user = userEvent.setup();
    let failA: (reason: unknown) => void = () => undefined;
    let finishB: (value: unknown) => void = () => undefined;
    api.mockImplementation((path: string) => {
      const url = String(path);
      if (url.includes("/search-runs/1/")) {
        return new Promise((_, reject) => {
          failA = reject;
        });
      }
      if (url.includes("/search-runs/2/")) {
        return new Promise((resolve) => {
          finishB = resolve;
        });
      }
      return Promise.reject(new Error(`unexpected ${url}`));
    });

    renderPage("/admin/search-runs/1");
    await user.click(screen.getByRole("button", { name: "open-2" }));
    finishB(resultsFor(2, "query-b"));
    expect(await screen.findByRole("heading", { name: "query-b" })).toBeInTheDocument();

    failA(new Error("stale failure"));
    await waitFor(() => {
      expect(screen.getByRole("heading", { name: "query-b" })).toBeInTheDocument();
    });
    expect(screen.queryByText("stale failure")).not.toBeInTheDocument();
  });

  it("keeps loading for B when error A arrives first", async () => {
    const user = userEvent.setup();
    let failA: (reason: unknown) => void = () => undefined;
    let finishB: (value: unknown) => void = () => undefined;
    api.mockImplementation((path: string) => {
      const url = String(path);
      if (url.includes("/search-runs/1/")) {
        return new Promise((_, reject) => {
          failA = reject;
        });
      }
      if (url.includes("/search-runs/2/")) {
        return new Promise((resolve) => {
          finishB = resolve;
        });
      }
      return Promise.reject(new Error(`unexpected ${url}`));
    });

    renderPage("/admin/search-runs/1");
    await user.click(screen.getByRole("button", { name: "open-2" }));
    expect(screen.getByText("Loading search results…")).toBeInTheDocument();

    failA(new Error("gone-a"));
    await waitFor(() => {
      expect(screen.getByText("Loading search results…")).toBeInTheDocument();
    });
    expect(screen.queryByText("gone-a")).not.toBeInTheDocument();

    finishB(resultsFor(2, "query-b"));
    expect(await screen.findByRole("heading", { name: "query-b" })).toBeInTheDocument();
  });

  it("replaces a previous error with a successful newer result", async () => {
    const user = userEvent.setup();
    let finishB: (value: unknown) => void = () => undefined;
    api.mockRejectedValueOnce(new Error("first failed"));
    renderPage("/admin/search-runs/1");
    expect(await screen.findByText("first failed")).toBeInTheDocument();

    api.mockImplementation((path: string) => {
      if (String(path).includes("/search-runs/2/")) {
        return new Promise((resolve) => {
          finishB = resolve;
        });
      }
      return Promise.reject(new Error(`unexpected ${path}`));
    });
    await user.click(screen.getByRole("button", { name: "open-2" }));
    expect(screen.getByText("Loading search results…")).toBeInTheDocument();
    expect(screen.queryByText("first failed")).not.toBeInTheDocument();
    finishB(resultsFor(2, "query-b"));
    expect(await screen.findByRole("heading", { name: "query-b" })).toBeInTheDocument();
  });

  it("does not surface abort as a user-visible error", async () => {
    const user = userEvent.setup();
    api.mockImplementation((path: string, init?: RequestInit) => {
      const url = String(path);
      if (url.includes("/search-runs/1/")) {
        return new Promise((_, reject) => {
          init?.signal?.addEventListener(
            "abort",
            () => reject(new DOMException("Aborted", "AbortError")),
            { once: true },
          );
        });
      }
      if (url.includes("/search-runs/2/")) {
        return Promise.resolve(resultsFor(2, "query-b"));
      }
      return Promise.reject(new Error(`unexpected ${url}`));
    });

    renderPage("/admin/search-runs/1");
    expect(screen.getByText("Loading search results…")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "open-2" }));
    expect(await screen.findByRole("heading", { name: "query-b" })).toBeInTheDocument();
    expect(screen.queryByText("Aborted")).not.toBeInTheDocument();
    expect(screen.queryByText("AbortError")).not.toBeInTheDocument();
  });

  it("ignores a response that arrives after unmount", async () => {
    let finish: (value: unknown) => void = () => undefined;
    api.mockImplementation(
      () =>
        new Promise((resolve) => {
          finish = resolve;
        }),
    );
    const view = renderPage("/admin/search-runs/1");
    expect(screen.getByText("Loading search results…")).toBeInTheDocument();
    view.unmount();
    finish(resultsFor(1, "query-a"));
    await waitFor(() => {
      expect(screen.queryByRole("heading", { name: "query-a" })).not.toBeInTheDocument();
    });
  });
});
