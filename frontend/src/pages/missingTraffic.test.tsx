import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { dashboardReady, repository, traffic } from "../test/fixtures";
import { DashboardPage } from "./DashboardPage";
import { RepositoryDetailsPage } from "./RepositoryDetailsPage";

const { api } = vi.hoisted(() => ({ api: vi.fn() }));

vi.mock("../lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../lib/api")>();
  return { ...actual, api };
});

describe("missing traffic", () => {
  beforeEach(() => {
    api.mockReset();
  });

  it("opens the portfolio list and keeps the warning off the repository name", async () => {
    const user = userEvent.setup();
    api.mockImplementation((path: string) => {
      if (!String(path).includes("/dashboard")) {
        return Promise.resolve({});
      }
      return Promise.resolve({
        ...dashboardReady,
        partialData: null,
        repositories: [
          {
            ...dashboardReady.repositories[0],
            collectionStatus: "SUCCESS",
            trafficNotice: { reason: "GITHUB_DATA_MISSING", date: "2026-10-18", lastTrafficDate: "2026-10-17" },
          },
          {
            ...dashboardReady.repositories[0],
            id: 8,
            fullName: "KHolodilin/other",
            collectionStatus: "PARTIAL",
            jobs: [
              { jobType: "TRAFFIC", status: "FAILED" },
              { jobType: "REFERRERS", status: "SUCCESS" },
            ],
            trafficNotice: { reason: "COLLECTION_FAILED", date: "2026-10-19", lastTrafficDate: "2026-10-18" },
          },
        ],
      });
    });
    render(
      <MemoryRouter>
        <DashboardPage />
      </MemoryRouter>,
    );
    expect(await screen.findByRole("button", { name: /2 repositories/ })).toBeInTheDocument();
    expect(screen.queryByText(/collected partially/)).not.toBeInTheDocument();
    expect(screen.queryByText(/service was not running/)).not.toBeInTheDocument();
    expect(screen.queryByText("Partial data")).not.toBeInTheDocument();
    const icon = screen.getByRole("img", { name: "No GitHub traffic for 18 Oct. Last data Oct 17" });
    expect(icon).toHaveTextContent("!");
    expect(icon.closest("a")).toBeNull();
    const badge = screen.getByRole("button", { name: /2 repositories/ });
    await user.click(badge);
    expect(badge).toHaveAttribute("aria-expanded", "true");
    expect(screen.getByText("Collection failed for Oct 19.")).toBeInTheDocument();
    expect(screen.getByText("Partial collection: Traffic ✕")).toBeInTheDocument();
    expect(screen.getAllByRole("link", { name: "KHolodilin/other" })).toHaveLength(2);
    await user.keyboard("{Escape}");
    expect(screen.queryByText("Collection failed for Oct 19.")).not.toBeInTheDocument();
    expect(badge).toHaveAttribute("aria-expanded", "false");
    await user.click(badge);
    expect(screen.getByText("Collection failed for Oct 19.")).toBeInTheDocument();
    await user.click(document.body);
    expect(screen.queryByText("Collection failed for Oct 19.")).not.toBeInTheDocument();
  });

  it("explains a missing yesterday on the repository page without drawing it as zero", async () => {
    api.mockImplementation((path: string) => {
      const url = String(path);
      if (url.endsWith("/repositories/13")) {
        return Promise.resolve(repository);
      }
      if (url.includes("/traffic?")) {
        return Promise.resolve({
          ...traffic,
          traffic: [{ date: "2026-10-17", views: 4, uniqueVisitors: 2, clones: 1, uniqueCloners: 1 }],
          trafficGap: { reason: "GITHUB_DATA_MISSING", date: "2026-10-18", lastTrafficDate: "2026-10-17" },
          lastCollection: {
            ...traffic.lastCollection,
            businessDate: "2026-10-19",
            status: "SUCCESS",
            jobs: [
              { jobType: "TRAFFIC", status: "SUCCESS" },
              { jobType: "REFERRERS", status: "SUCCESS" },
            ],
          },
        });
      }
      return Promise.resolve([]);
    });
    render(
      <MemoryRouter initialEntries={["/repositories/13?tab=overview"]}>
        <Routes>
          <Route path="/repositories/:id" element={<RepositoryDetailsPage />} />
        </Routes>
      </MemoryRouter>,
    );
    expect(await screen.findByText("⚠ Traffic — GitHub data missing for Oct 18 (T-1)")).toBeInTheDocument();
    expect(screen.getByText(/SUCCESS/)).toBeInTheDocument();
    expect(screen.getByText("Referrers")).toBeInTheDocument();
    expect(screen.queryByText("✓ Referrers")).not.toBeInTheDocument();
  });

  it("says the collection never started after the window closed", async () => {
    api.mockImplementation((path: string) => {
      const url = String(path);
      if (url.endsWith("/repositories/13")) {
        return Promise.resolve(repository);
      }
      if (url.includes("/traffic?")) {
        return Promise.resolve({
          ...traffic,
          trafficGap: { reason: "COLLECTION_NOT_EXECUTED", date: "2026-10-19", lastTrafficDate: null },
        });
      }
      return Promise.resolve([]);
    });
    render(
      <MemoryRouter initialEntries={["/repositories/13?tab=overview"]}>
        <Routes>
          <Route path="/repositories/:id" element={<RepositoryDetailsPage />} />
        </Routes>
      </MemoryRouter>,
    );
    expect(await screen.findByText("⛔ Collection not executed for Oct 19.")).toBeInTheDocument();
    expect(screen.queryByText("✓ Traffic")).not.toBeInTheDocument();
  });
});
