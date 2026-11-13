import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";
import { repository, searchHistory, searchResults } from "../test/fixtures";
import { QueryDetailsPage } from "./QueryDetailsPage";

const { api } = vi.hoisted(() => ({ api: vi.fn() }));
vi.mock("../lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../lib/api")>();
  return { ...actual, api };
});

describe("QueryDetailsPage run now", () => {
  it("waits for the collection request before reloading history", async () => {
    const user = userEvent.setup();
    const requestStates = [{ state: "ACTIVE" }, { state: "SUCCEEDED" }];
    api.mockImplementation(async (path: string) => {
      const url = String(path);
      if (url.includes("/api/v1/public/resolve")) {
        return { ownerId: "1", repositoryId: "13", canonicalPath: "/acme/outbox", redirect: false };
      }
      if (url.endsWith("/admin/repositories/13")) {
        return repository;
      }
      if (url.includes("/search-queries/") && url.includes("history")) {
        return searchHistory;
      }
      if (url.includes("/search-queries/") && url.includes("results")) {
        return searchResults;
      }
      if (url.includes("/search-queries/") && url.includes("series")) {
        return [];
      }
      if (url.includes("/search-queries/") && url.includes("/run")) {
        return { requestId: "req-wait", taskIds: ["t1"], coalesced: false };
      }
      if (url.includes("/collection-requests/req-wait")) {
        return requestStates.length > 1 ? requestStates.shift()! : requestStates[0];
      }
      return [];
    });

    render(
      <MemoryRouter initialEntries={["/acme/outbox/search-queries/5"]}>
        <Routes>
          <Route path="/:owner/:repository/search-queries/:queryId" element={<QueryDetailsPage />} />
        </Routes>
      </MemoryRouter>,
    );

    expect(await screen.findByRole("button", { name: "Run now" })).toBeInTheDocument();
    const historyBefore = api.mock.calls.filter(([url]) => String(url).includes("/history")).length;
    await user.click(screen.getByRole("button", { name: "Run now" }));

    await waitFor(
      () => {
        expect(api.mock.calls.some(([url]) => String(url).includes("/collection-requests/req-wait"))).toBe(true);
        expect(api.mock.calls.filter(([url]) => String(url).includes("/collection-requests/req-wait")).length).toBeGreaterThanOrEqual(2);
        expect(api.mock.calls.filter(([url]) => String(url).includes("/history")).length).toBeGreaterThan(historyBefore);
      },
      { timeout: 5000 },
    );
  });
});
