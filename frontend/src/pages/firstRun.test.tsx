import { act, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { Layout } from "../components/Layout";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { dashboardReady } from "../test/fixtures";
import { TRAFFIC_PERMISSION } from "../lib/githubAccess";
import { DashboardPage } from "./DashboardPage";

const { api } = vi.hoisted(() => ({ api: vi.fn() }));
vi.mock("../lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../lib/api")>();
  return { ...actual, api };
});

function status(tokenState: string, open = false, timezone = "UTC") {
  return {
    tokenState,
    rateLimitReset: null,
    planner: { timezone, from: "10:00", to: "18:00", open },
  };
}

function renderDashboard() {
  return render(
    <MemoryRouter>
      <DashboardPage />
    </MemoryRouter>,
  );
}

describe("first-run portfolio", () => {
  beforeEach(() => {
    api.mockReset();
  });

  it("keeps the token row open until GitHub accepts it", async () => {
    api.mockImplementation((path: string) => {
      if (String(path).includes("/system/status")) {
        return Promise.resolve(status("REJECTED"));
      }
      return Promise.resolve({ ...dashboardReady, state: "NO_REPOSITORIES", repositories: [], traffic: [] });
    });
    renderDashboard();
    expect(await screen.findByText(/Stored data is kept/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Create a fine-grained token" })).toHaveAttribute(
      "href",
      "https://github.com/settings/personal-access-tokens/new",
    );
    expect(screen.getByText(/docker compose up -d/)).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Select repositories" })).not.toBeInTheDocument();
  });

  it("opens repository selection after the token is accepted", async () => {
    api.mockImplementation((path: string) => {
      if (String(path).includes("/system/status")) {
        return Promise.resolve(status("RATE_LIMITED", false, "Europe/Moscow"));
      }
      return Promise.resolve({ ...dashboardReady, state: "NO_REPOSITORIES", repositories: [], traffic: [] });
    });
    renderDashboard();
    expect(await screen.findByRole("link", { name: "Select repositories" })).toHaveAttribute("href", "/repositories");
    expect(screen.getByText(/10:00 to 18:00 Europe\/Moscow/)).toBeInTheDocument();
    expect(screen.getByText(/The first collection starts as soon as a repository is enabled/)).toBeInTheDocument();
    expect(screen.queryByText(/Read permissions/)).not.toBeInTheDocument();
  });

  it("stays on the token step when status cannot be read", async () => {
    api.mockImplementation((path: string) => {
      if (String(path).includes("/system/status")) {
        return Promise.reject(new Error("offline"));
      }
      return Promise.resolve({ ...dashboardReady, state: "NO_REPOSITORIES", repositories: [], traffic: [] });
    });
    renderDashboard();
    await waitFor(() => expect(api).toHaveBeenCalledWith("/api/v1/system/status"));
    expect(await screen.findByText("Application is running.")).toBeInTheDocument();
    expect(screen.getByText(/Read permissions/)).toBeInTheDocument();
    expect(screen.queryByText(/Stored data is kept/)).not.toBeInTheDocument();
    expect(screen.getByText(/10:00 to 18:00 UTC/)).toBeInTheDocument();
  });

  it("shows collecting jobs and a failed traffic permission before the chart", async () => {
    api.mockImplementation((path: string) => {
      if (String(path).includes("/system/status")) {
        return Promise.resolve(status("OK", true));
      }
      return Promise.resolve({
        ...dashboardReady,
        state: "FIRST_COLLECTION",
        traffic: [],
        repositories: [
          {
            ...dashboardReady.repositories[0],
            trafficPermissionMissing: true,
            jobs: [{ jobType: "TRAFFIC", status: "FAILED", errorMessage: "403" }],
          },
          {
            ...dashboardReady.repositories[0],
            id: 8,
            fullName: "KHolodilin/other",
            jobs: [{ jobType: "REFERRERS", status: "FAILED", errorMessage: "rate limit" }],
          },
        ],
      });
    });
    renderDashboard();
    expect(await screen.findByRole("heading", { name: "Collecting" })).toBeInTheDocument();
    expect(screen.getByText(TRAFFIC_PERMISSION)).toBeInTheDocument();
    expect(screen.getByText("rate limit")).toBeInTheDocument();
    expect(screen.queryByText(/Auto-collect runs every day/)).not.toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Traffic" })).not.toBeInTheDocument();
  });

  it("ignores a status result that arrives after leaving the page", async () => {
    const empty = { ...dashboardReady, state: "NO_REPOSITORIES", repositories: [], traffic: [] };
    let resolveStatus: (value: unknown) => void = () => undefined;
    api.mockImplementation((path: string) => {
      if (String(path).includes("/system/status")) {
        return new Promise((resolve) => {
          resolveStatus = resolve;
        });
      }
      return Promise.resolve(empty);
    });
    const accepted = renderDashboard();
    await waitFor(() => expect(api).toHaveBeenCalledWith("/api/v1/system/status"));
    accepted.unmount();
    await act(async () => {
      resolveStatus(status("OK", true));
    });

    let rejectStatus: (error: Error) => void = () => undefined;
    api.mockImplementation((path: string) => {
      if (String(path).includes("/system/status")) {
        return new Promise((_resolve, reject) => {
          rejectStatus = reject;
        });
      }
      return Promise.resolve(empty);
    });
    const failed = renderDashboard();
    await waitFor(() => expect(api.mock.calls.filter((call) => String(call[0]).includes("/system/status")).length).toBeGreaterThan(1));
    failed.unmount();
    await act(async () => {
      rejectStatus(new Error("offline"));
    });
  });

  it("explains the auto-collect window in the footer", async () => {
    api.mockImplementation((path: string) => {
      if (String(path).includes("/system/status")) {
        return Promise.resolve(status("OK", false));
      }
      return Promise.resolve(dashboardReady);
    });
    render(
      <MemoryRouter>
        <Routes>
          <Route element={<Layout />}>
            <Route path="/" element={<DashboardPage />} />
          </Route>
        </Routes>
      </MemoryRouter>,
    );
    const line = await screen.findByText(/It has not started yet today/);
    expect(line.closest("footer")).not.toBeNull();
    expect(screen.getByRole("heading", { name: "Portfolio" }).parentElement).not.toContainElement(line);
    expect(screen.getByRole("heading", { name: "Traffic" })).toBeInTheDocument();
  });
});
