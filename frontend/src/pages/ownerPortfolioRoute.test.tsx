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

const ownerDashboard = {
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
      return Promise.resolve(ownerDashboard);
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
      if (url.startsWith("/api/v1/owners?login=")) {
        return Promise.resolve({
          items: [{ ownerId: "1", login: "KHolodilin" }],
          nextCursor: null,
        });
      }
      if (url === "/api/v1/owners/1") {
        return Promise.resolve({
          ownerId: "1",
          login: "KHolodilin",
          ownerType: "USER",
        });
      }
      if (url.includes("/api/v1/dashboard?")) {
        return Promise.resolve(ownerDashboard);
      }
      if (url.includes("/growth-events")) {
        return Promise.resolve([]);
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
