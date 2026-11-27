import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";
import { anonymousMe } from "../lib/auth";
import { dashboardReady } from "../test/fixtures";

const { api, loadMe } = vi.hoisted(() => ({ api: vi.fn(), loadMe: vi.fn() }));
vi.mock("../lib/api", async (original) => ({
  ...(await original<typeof import("../lib/api")>()),
  api,
}));
vi.mock("../lib/auth", async (original) => ({
  ...(await original<typeof import("../lib/auth")>()),
  loadMe,
}));

import App from "../App";

const admin = {
  ...anonymousMe,
  authenticated: true,
  userId: "1",
  displayName: "Admin",
  roles: ["USER", "ADMIN"],
  actions: ["MUTATE"],
};

describe("owner portfolio route", () => {
  it("shows Disc. and Activity for admin like main Portfolio", async () => {
    loadMe.mockResolvedValue(admin);
    api.mockImplementation((path: string) => {
      const url = String(path);
      if (url.includes("/system/status")) {
        return Promise.resolve({
          tokenState: "OK",
          rateLimitReset: null,
          planner: { timezone: "UTC", from: "06:00", to: "18:00", open: false },
        });
      }
      return Promise.resolve({
        ...dashboardReady,
        repositories: [
          {
            ...dashboardReady.repositories[0],
            discoverabilityPassed: 12,
            discoverabilityTotal: 13,
            activityStatus: "ACTIVE",
            activityAt: "2026-09-23T00:00:00Z",
          },
        ],
      });
    });
    render(
      <MemoryRouter initialEntries={["/KHolodilin"]}>
        <App />
      </MemoryRouter>,
    );
    expect(await screen.findByRole("columnheader", { name: "Disc." })).toBeInTheDocument();
    expect(screen.getByRole("columnheader", { name: "Activity" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Disc\./ })).toBeInTheDocument();
    expect(screen.getByText("12")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "KHolodilin" })).toBeInTheDocument();
    expect(
      api.mock.calls.some(
        ([path]) =>
          (String(path).includes("/api/v1/dashboard?") ||
            String(path).includes("/api/v1/collection-status?")) &&
          String(path).includes("owner=KHolodilin"),
      ),
    ).toBe(true);
  });

  it("shows Disc. and Activity on the public owner dashboard for anonymous", async () => {
    loadMe.mockResolvedValue(anonymousMe);
    api.mockImplementation((path: string) => {
      const url = String(path);
      if (url.includes("/api/v1/public/owners/")) {
        if (url.endsWith("/KHolodilin") || url.match(/owners\/[^/?]+$/)) {
          return Promise.resolve({
            owner: { id: "1", login: "KHolodilin", type: "USER" },
            canonicalPath: "/KHolodilin",
            repositoryCount: 1,
            modules: {
              traffic: true,
              referrers: false,
              paths: false,
              search: false,
              topics: false,
            },
            freshness: { state: "READY", lastSuccessfulSnapshotAt: "2026-09-23T00:00:00Z" },
          });
        }
        if (url.includes("/stats?")) {
          return Promise.resolve({
            period: "30d",
            from: "2026-08-24",
            to: "2026-09-23",
            repositories: 1,
            totals: {
              stars: { value: 1, absoluteChange: 0, growthPercent: 0, complete: true },
              forks: { value: 0, absoluteChange: 0, growthPercent: 0, complete: true },
              watchers: { value: 0, absoluteChange: 0, growthPercent: 0, complete: true },
              contributors: { value: 1, absoluteChange: 0, growthPercent: 0, complete: true },
            },
          });
        }
        if (url.includes("/traffic?")) {
          return Promise.resolve({
            totals: {
              views: { value: 1, absoluteChange: 0, growthPercent: 0, complete: true },
              visitors: { value: 1, absoluteChange: 0, growthPercent: 0, complete: true },
              clones: { value: 0, absoluteChange: 0, growthPercent: 0, complete: true },
              uniqueCloners: { value: 0, absoluteChange: 0, growthPercent: 0, complete: true },
            },
          });
        }
        if (url.includes("-history")) {
          return Promise.resolve({ items: [], nextDate: null });
        }
        if (url.includes("/repositories?")) {
          return Promise.resolve({
            items: [
              {
                id: "13",
                name: "outbox",
                visitors: 1,
                views: 1,
                clones: 0,
                stars: 1,
                forks: 0,
                growthPercent: 0,
                activityAt: "2026-09-23T00:00:00Z",
                activityStatus: "ACTIVE",
                discoverabilityPassed: 12,
                discoverabilityTotal: 13,
              },
            ],
            nextCursor: null,
          });
        }
        if (url.includes("/growth-events")) {
          return Promise.resolve({ items: [], nextCursor: null });
        }
      }
      return Promise.resolve({});
    });
    render(
      <MemoryRouter initialEntries={["/KHolodilin"]}>
        <App />
      </MemoryRouter>,
    );
    expect(await screen.findByRole("columnheader", { name: "Disc." })).toBeInTheDocument();
    expect(screen.getByRole("columnheader", { name: "Activity" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Disc\./ })).toBeInTheDocument();
    expect(screen.getByText("12")).toBeInTheDocument();
  });
});
