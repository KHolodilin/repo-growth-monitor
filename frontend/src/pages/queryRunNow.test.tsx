import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";
import { repository, searchHistory, searchResults } from "../test/fixtures";
import { emptyCursorPage, isOwnersCatalog, respondCatalogResolve } from "../test/repoRoutes";
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
    api.mockImplementation((path: string) => {
      const url = String(path);
      const catalog = respondCatalogResolve(url);
      if (catalog) return Promise.resolve(catalog);
      if (isOwnersCatalog(url)) return Promise.resolve(emptyCursorPage);
      if (url.endsWith("/repositories/13")) {
        return Promise.resolve(repository);
      }
      if (url.includes("/search-queries/") && url.includes("history")) {
        return Promise.resolve(searchHistory);
      }
      if (url.includes("/search-queries/") && url.includes("results")) {
        return Promise.resolve(searchResults);
      }
      if (url.includes("/search-queries/") && url.includes("series")) {
        return Promise.resolve([]);
      }
      if (url.includes("/search-queries/") && url.includes("/run")) {
        return Promise.resolve({ requestId: "req-wait", taskIds: ["t1"], coalesced: false });
      }
      if (url.includes("/collection-requests/req-wait")) {
        return Promise.resolve(
          requestStates.length > 1 ? requestStates.shift()! : requestStates[0],
        );
      }
      return Promise.resolve([]);
    });

    render(
      <MemoryRouter initialEntries={["/acme/outbox/search-queries/5"]}>
        <Routes>
          <Route
            path="/:owner/:repository/search-queries/:queryId"
            element={<QueryDetailsPage />}
          />
        </Routes>
      </MemoryRouter>,
    );

    expect(await screen.findByRole("button", { name: "Run now" })).toBeInTheDocument();
    const historyBefore = api.mock.calls.filter(([url]) => String(url).includes("/history")).length;
    await user.click(screen.getByRole("button", { name: "Run now" }));

    await waitFor(
      () => {
        expect(
          api.mock.calls.some(([url]) => String(url).includes("/collection-requests/req-wait")),
        ).toBe(true);
        expect(
          api.mock.calls.filter(([url]) => String(url).includes("/collection-requests/req-wait"))
            .length,
        ).toBeGreaterThanOrEqual(2);
        expect(
          api.mock.calls.filter(([url]) => String(url).includes("/history")).length,
        ).toBeGreaterThan(historyBefore);
      },
      { timeout: 5000 },
    );
  });
});
