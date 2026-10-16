import { fireEvent, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { Layout } from "../components/Layout";
import { TOKEN_MISSING, TOKEN_REJECTED, TRAFFIC_PERMISSION } from "../lib/githubAccess";
import { dashboardReady, repository, traffic } from "../test/fixtures";
import { DashboardPage } from "./DashboardPage";
import { RepositoriesPage } from "./RepositoriesPage";
import { RepositoryDetailsPage } from "./RepositoryDetailsPage";

const { api } = vi.hoisted(() => ({ api: vi.fn() }));
vi.mock("../lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../lib/api")>();
  return { ...actual, api };
});

const tracked = { ...repository, accessible: true, trafficPermissionMissing: false };
const privateRepo = {
  ...repository,
  id: 8,
  name: "secret",
  fullName: "KHolodilin/secret",
  visibility: "PRIVATE",
  trackingEnabled: true,
  accessible: true,
  trafficPermissionMissing: false,
};
const untracked = {
  ...repository,
  id: 10,
  name: "extra",
  fullName: "Other/extra",
  trackingEnabled: false,
  accessible: true,
  owner: { ...repository.owner, login: "Other" },
};
const lost = {
  ...repository,
  id: 9,
  name: "gone",
  fullName: "KHolodilin/gone",
  trackingEnabled: true,
  accessible: false,
  trafficPermissionMissing: true,
};

const listed = [tracked, privateRepo, untracked, lost];

function renderRepos() {
  return render(
    <MemoryRouter>
      <RepositoriesPage />
    </MemoryRouter>,
  );
}

describe("repository filters", () => {
  beforeEach(() => {
    sessionStorage.clear();
    api.mockReset();
  });

  it("filters the saved list and changes tracking for the rows on screen", async () => {
    const user = userEvent.setup();
    api.mockImplementation((path: string) => {
      const url = String(path);
      if (url.includes("/system/status")) {
        return { tokenState: "OK", rateLimitReset: null };
      }
      if (url.includes("/tracking")) {
        const id = Number(url.match(/repositories\/(\d+)/)?.[1]);
        const repo = listed.find((item) => item.id === id) ?? tracked;
        return { ...repo, trackingEnabled: !repo.trackingEnabled };
      }
      return listed;
    });
    renderRepos();
    expect(await screen.findByText("Tracking 3 of 4")).toBeInTheDocument();
    expect(screen.getByText(tracked.fullName)).toBeInTheDocument();
    expect(screen.queryByText(privateRepo.fullName)).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "1 private hidden" })).toBeInTheDocument();
    expect(screen.getByText(/The token can no longer see this repository/)).toBeInTheDocument();
    expect(screen.getByText(TRAFFIC_PERMISSION)).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "1 private hidden" }));
    expect(screen.getByText(privateRepo.fullName)).toBeInTheDocument();
    expect(screen.getByText(/Public search and topics do not rank this repository/)).toBeInTheDocument();
    expect(sessionStorage.getItem("rgm.repositories.visibility")).toBe("private");

    await user.click(within(screen.getByRole("group", { name: "Visibility" })).getByRole("button", { name: "Private" }));
    await user.click(within(screen.getByRole("group", { name: "Visibility" })).getByRole("button", { name: "Public" }));
    expect(screen.queryByText(privateRepo.fullName)).not.toBeInTheDocument();
    await user.click(within(screen.getByRole("group", { name: "Visibility" })).getByRole("button", { name: "All" }));
    await user.click(within(screen.getByRole("group", { name: "Tracking" })).getByRole("button", { name: "All" }));
    await user.type(screen.getByLabelText("Search repositories"), "other");
    expect(screen.getByText(untracked.fullName)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Start tracking (1)" }));
    expect(await screen.findByText("Tracking 4 of 4")).toBeInTheDocument();

    vi.spyOn(window, "confirm").mockReturnValueOnce(false);
    await user.clear(screen.getByLabelText("Search repositories"));
    await user.click(screen.getByRole("button", { name: "No access" }));
    const trackingCalls = () => api.mock.calls.filter((call) => String(call[0]).includes("/tracking")).length;
    const beforeCancel = trackingCalls();
    await user.click(screen.getByRole("button", { name: "Stop tracking (1)" }));
    expect(trackingCalls()).toBe(beforeCancel);
    vi.spyOn(window, "confirm").mockReturnValueOnce(true);
    api.mockImplementation((path: string) => {
      const url = String(path);
      if (url.includes("/tracking")) {
        return { ...lost, trackingEnabled: false };
      }
      return listed;
    });
    await user.click(screen.getByRole("button", { name: "Stop tracking (1)" }));
    expect(await screen.findByText("Tracking 3 of 4")).toBeInTheDocument();
    vi.spyOn(window, "confirm").mockRestore();
  });

  it("reads the saved list and refreshes once when a token is new", async () => {
    const calls: string[] = [];
    api.mockImplementation((path: string) => {
      const url = String(path);
      calls.push(url);
      if (url.includes("/system/status")) {
        return { tokenState: "OK", rateLimitReset: null };
      }
      if (url.includes("refresh=true")) {
        return [{ ...tracked, trackingEnabled: false }];
      }
      return [];
    });
    renderRepos();
    expect(await screen.findByText(tracked.fullName)).toBeInTheDocument();
    expect(calls.filter((url) => url.includes("refresh=true"))).toHaveLength(1);
    expect(screen.getByRole("button", { name: "All", pressed: true })).toBeInTheDocument();

    api.mockImplementation(async (path: string) => {
      if (String(path).includes("/system/status")) {
        return { tokenState: "MISSING", rateLimitReset: null };
      }
      return [];
    });
    const empty = renderRepos();
    expect(await screen.findByText("No repositories yet. Configure GITHUB_TOKEN and refresh.")).toBeInTheDocument();
    empty.unmount();
  });

  it("shows token banners and does not start collection", async () => {
    api.mockImplementation((path: string) => {
      if (String(path).includes("/system/status")) {
        return { tokenState: "MISSING", rateLimitReset: null };
      }
      return dashboardReady;
    });
    const missing = render(
      <MemoryRouter>
        <Routes>
          <Route element={<Layout />}>
            <Route path="/" element={<p>home</p>} />
          </Route>
        </Routes>
      </MemoryRouter>,
    );
    expect(await screen.findByText(TOKEN_MISSING)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Collect now" })).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "Collect now" }));
    expect(api).not.toHaveBeenCalledWith(expect.stringContaining("/collect"), expect.anything());
    missing.unmount();

    api.mockImplementation((path: string) => {
      if (String(path).includes("/system/status")) {
        return { tokenState: "REJECTED", rateLimitReset: null };
      }
      if (String(path).includes("/repositories")) {
        return [lost];
      }
      return dashboardReady;
    });
    renderRepos();
    expect(await screen.findByText(lost.fullName)).toBeInTheDocument();
    expect(screen.queryByText(/can no longer see/)).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Refresh from GitHub" })).toBeDisabled();

    api.mockImplementation((path: string) => {
      if (String(path).includes("/system/status")) {
        return { tokenState: "RATE_LIMITED", rateLimitReset: "not-a-date" };
      }
      return dashboardReady;
    });
    render(
      <MemoryRouter>
        <Routes>
          <Route element={<Layout />}>
            <Route path="/" element={<p>home</p>} />
          </Route>
        </Routes>
      </MemoryRouter>,
    );
    expect(await screen.findByText("Collection continues after the rate limit resets.")).toBeInTheDocument();
  });

  it("shows internal repositories, portfolio marks, and a rejected token on the repository page", async () => {
    const user = userEvent.setup();
    const internal = {
      ...tracked,
      id: 11,
      name: "inside",
      fullName: "Org/inside",
      visibility: "INTERNAL",
    };
    api.mockImplementation(async (path: string) => {
      if (String(path).includes("/system/status")) {
        return { tokenState: "OK", rateLimitReset: null };
      }
      return [tracked, internal];
    });
    const list = renderRepos();
    expect(await screen.findByRole("button", { name: "Internal" })).toBeInTheDocument();
    expect(within(screen.getByRole("group", { name: "Visibility" })).getByRole("button", { name: "All" })).toHaveAttribute(
      "aria-pressed",
      "true",
    );
    await user.click(screen.getByRole("button", { name: "Not tracked" }));
    expect(screen.queryByText(tracked.fullName)).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Internal" }));
    await user.click(screen.getByRole("button", { name: "Tracked" }));
    expect(screen.getByText(internal.fullName)).toBeInTheDocument();
    expect(screen.queryByText(tracked.fullName)).not.toBeInTheDocument();
    list.unmount();

    api.mockImplementation(async (path: string) => {
      if (String(path).includes("/system/status")) {
        return { tokenState: "REJECTED", rateLimitReset: null };
      }
      if (String(path).includes("/dashboard")) {
        return {
          ...dashboardReady,
          repositories: [{ ...dashboardReady.repositories[0], accessible: false, trafficPermissionMissing: true }],
        };
      }
      return dashboardReady;
    });
    const portfolio = render(
      <MemoryRouter>
        <DashboardPage />
      </MemoryRouter>,
    );
    expect(await screen.findByText(repository.fullName)).toBeInTheDocument();
    expect(screen.queryByText(/can no longer see/)).not.toBeInTheDocument();
    portfolio.unmount();

    api.mockImplementation(async (path: string) => {
      const url = String(path);
      if (url.includes("/system/status")) {
        return { tokenState: "OK", rateLimitReset: "2026-10-16T18:00:00Z" };
      }
      if (url.endsWith("/repositories/13")) {
        return { ...repository, accessible: false, trafficPermissionMissing: true };
      }
      if (url.includes("/traffic?")) {
        return traffic;
      }
      return [];
    });
    render(
      <MemoryRouter initialEntries={["/repositories/13"]}>
        <Routes>
          <Route path="/repositories/:id" element={<RepositoryDetailsPage />} />
        </Routes>
      </MemoryRouter>,
    );
    expect(await screen.findByText(/can no longer see this repository/)).toBeInTheDocument();
    expect(screen.getByText(TRAFFIC_PERMISSION)).toBeInTheDocument();

    api.mockImplementation(async (path: string) => {
      if (String(path).includes("/system/status")) {
        return { tokenState: "REJECTED", rateLimitReset: null };
      }
      return dashboardReady;
    });
    render(
      <MemoryRouter>
        <Routes>
          <Route element={<Layout />}>
            <Route path="/" element={<p>home</p>} />
          </Route>
        </Routes>
      </MemoryRouter>,
    );
    expect(await screen.findByText(TOKEN_REJECTED)).toBeInTheDocument();

    api.mockImplementation(async (path: string) => {
      if (String(path).includes("/system/status")) {
        return { tokenState: "RATE_LIMITED", rateLimitReset: "2026-10-16T18:00:00Z" };
      }
      return dashboardReady;
    });
    render(
      <MemoryRouter>
        <Routes>
          <Route element={<Layout />}>
            <Route path="/" element={<p>later</p>} />
          </Route>
        </Routes>
      </MemoryRouter>,
    );
    expect(await screen.findByText(/Collection continues after/)).toBeInTheDocument();
  });

  it("reports tracking failures and keeps a blocked refresh on the saved list", async () => {
    const user = userEvent.setup();
    const hidden = { ...untracked, id: 12, fullName: "Other/hidden", accessible: false, trackingEnabled: false };
    api.mockImplementation(async (path: string) => {
      const url = String(path);
      if (url.includes("/system/status")) {
        return { tokenState: "OK", rateLimitReset: null };
      }
      if (url.includes("/tracking")) {
        throw new Error("tracking failed");
      }
      return [...listed, hidden];
    });
    const page = renderRepos();
    expect(await screen.findByText("Tracking 3 of 5")).toBeInTheDocument();
    const confirm = vi.spyOn(window, "confirm").mockReturnValueOnce(false);
    const trackedRow = screen.getByText(tracked.fullName).closest("label");
    const trackingCalls = () => api.mock.calls.filter((call) => String(call[0]).includes("/tracking")).length;
    const beforeCancel = trackingCalls();
    await user.click(within(trackedRow as HTMLElement).getByRole("checkbox"));
    expect(confirm).toHaveBeenCalledWith("Stop tracking this repository? Collection stops. Stored data stays.");
    expect(trackingCalls()).toBe(beforeCancel);
    expect(within(trackedRow as HTMLElement).getByRole("checkbox")).toBeChecked();
    confirm.mockReturnValueOnce(true);
    await user.click(within(trackedRow as HTMLElement).getByRole("checkbox"));
    expect(await screen.findByText("tracking failed")).toBeInTheDocument();
    confirm.mockRestore();

    await user.click(within(screen.getByRole("group", { name: "Tracking" })).getByRole("button", { name: "All" }));
    const hiddenRow = screen.getByText(hidden.fullName).closest("label");
    expect(within(hiddenRow as HTMLElement).getByRole("checkbox")).toBeDisabled();

    await user.type(screen.getByRole("textbox", { name: "Search repositories" }), "other");
    await user.click(screen.getByRole("button", { name: /Start tracking/ }));
    expect(await screen.findByText("tracking failed")).toBeInTheDocument();
    page.unmount();
    sessionStorage.clear();

    api.mockImplementation(async (path: string) => {
      if (String(path).includes("/system/status")) {
        return { tokenState: "REJECTED", rateLimitReset: null };
      }
      return listed;
    });
    const blocked = renderRepos();
    expect(await screen.findByText(tracked.fullName)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Refresh from GitHub" })).toBeDisabled();
    expect(api).not.toHaveBeenCalledWith("/api/v1/repositories?refresh=true");
    blocked.unmount();

    const getItem = vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    const stored = renderRepos();
    expect(await screen.findByText(tracked.fullName)).toBeInTheDocument();
    stored.unmount();
    getItem.mockRestore();
    sessionStorage.clear();

    const setItem = vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    renderRepos();
    await user.type(await screen.findByRole("textbox", { name: "Search repositories" }), "secret");
    expect(screen.getByRole("textbox", { name: "Search repositories" })).toHaveValue("secret");
    setItem.mockRestore();
  });

  it("stops repository collection when the token is rejected and shows an undated rate limit", async () => {
    const user = userEvent.setup();
    api.mockImplementation(async (path: string) => {
      const url = String(path);
      if (url.includes("/system/status")) {
        return { tokenState: "REJECTED", rateLimitReset: null };
      }
      if (url.endsWith("/repositories/13")) {
        return repository;
      }
      if (url.includes("/traffic?")) {
        return { ...traffic, lastCollection: undefined };
      }
      return [];
    });
    const details = render(
      <MemoryRouter initialEntries={["/repositories/13?tab=overview"]}>
        <Routes>
          <Route path="/repositories/:id" element={<RepositoryDetailsPage />} />
        </Routes>
      </MemoryRouter>,
    );
    expect(await screen.findByText("No collection data yet.")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Collect now" }));
    expect(await screen.findByText(TOKEN_REJECTED)).toBeInTheDocument();
    expect(api).not.toHaveBeenCalledWith(expect.stringContaining("/collect"), expect.anything());
    details.unmount();

    let releaseDetails: (value: unknown) => void = () => undefined;
    api.mockImplementation((path: string) => {
      const url = String(path);
      if (url.includes("/system/status")) {
        return new Promise((resolve) => {
          releaseDetails = resolve;
        });
      }
      if (url.endsWith("/repositories/13")) {
        return Promise.resolve({ ...repository, accessible: true, trafficPermissionMissing: true });
      }
      if (url.includes("/traffic?")) {
        return Promise.resolve({ ...traffic, lastCollection: undefined });
      }
      return Promise.resolve([]);
    });
    const pendingDetails = render(
      <MemoryRouter initialEntries={["/repositories/13?tab=overview"]}>
        <Routes>
          <Route path="/repositories/:id" element={<RepositoryDetailsPage />} />
        </Routes>
      </MemoryRouter>,
    );
    expect(await screen.findByText(repository.fullName)).toBeInTheDocument();
    pendingDetails.unmount();
    releaseDetails({ tokenState: "OK", rateLimitReset: null });
    await new Promise((resolve) => setTimeout(resolve, 0));

    api.mockImplementation(async (path: string) => {
      if (String(path).includes("/system/status")) {
        return { tokenState: "RATE_LIMITED", rateLimitReset: null };
      }
      if (String(path).includes("/dashboard")) {
        return dashboardReady;
      }
      return { tokenState: "WEIRD" };
    });
    render(
      <MemoryRouter>
        <Routes>
          <Route element={<Layout />}>
            <Route path="/" element={<p>home</p>} />
          </Route>
        </Routes>
      </MemoryRouter>,
    );
    expect(await screen.findByText("Collection continues after the rate limit resets.")).toBeInTheDocument();

    let releaseStatus: (value: unknown) => void = () => undefined;
    api.mockImplementation(async (path: string) => {
      if (String(path).includes("/system/status")) {
        return new Promise((resolve) => {
          releaseStatus = resolve;
        });
      }
      if (String(path).includes("/dashboard")) {
        return {
          ...dashboardReady,
          repositories: [{ ...dashboardReady.repositories[0], accessible: false, trafficPermissionMissing: true }],
        };
      }
      return dashboardReady;
    });
    const pending = render(
      <MemoryRouter>
        <DashboardPage />
      </MemoryRouter>,
    );
    expect(await screen.findByText(repository.fullName)).toBeInTheDocument();
    pending.unmount();
    releaseStatus({ tokenState: "OK", rateLimitReset: null });
    await new Promise((resolve) => setTimeout(resolve, 0));
  });

  it("updates the row when the checkbox turns tracking off", async () => {
    const user = userEvent.setup();
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(true);
    api.mockImplementation(async (path: string) => {
      if (String(path).includes("/system/status")) {
        return { tokenState: "OK", rateLimitReset: null };
      }
      if (String(path).includes("/tracking")) {
        return { ...tracked, trackingEnabled: false };
      }
      return [tracked, privateRepo];
    });
    renderRepos();
    expect(await screen.findByText(tracked.fullName)).toBeInTheDocument();
    const row = screen.getByText(tracked.fullName).closest("label");
    await user.click(within(row as HTMLElement).getByRole("checkbox"));
    expect(await screen.findByText("Tracking 1 of 2")).toBeInTheDocument();
    confirm.mockRestore();
  });
});
