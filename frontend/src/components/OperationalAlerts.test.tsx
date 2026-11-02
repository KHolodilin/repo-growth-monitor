import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { AuthProvider } from "../lib/AuthContext";
import { anonymousMe } from "../lib/auth";
import { NO_ACCESS, TOKEN_MISSING, TOKEN_REJECTED, TRAFFIC_PERMISSION } from "../lib/githubAccess";
import { dashboardReady } from "../test/fixtures";
import { Layout } from "./Layout";
import { RepositoryAccessNote } from "./RepositoryAccessNote";

const { api, loadMe, signOut } = vi.hoisted(() => ({ api: vi.fn(), loadMe: vi.fn(), signOut: vi.fn() }));
vi.mock("../lib/api", async (original) => ({ ...await original<typeof import("../lib/api")>(), api }));
vi.mock("../lib/auth", async (original) => ({ ...await original<typeof import("../lib/auth")>(), loadMe, signOut }));
const registered = { ...anonymousMe, authenticated: true, userId: "1", displayName: "User", roles: ["USER"], actions: [] };
const admin = { ...registered, roles: ["USER", "ADMIN"], actions: ["MUTATE"] };
const banners = [["MISSING", TOKEN_MISSING], ["REJECTED", TOKEN_REJECTED], ["RATE_LIMITED", "Collection continues after the rate limit resets."]] as const;
let tokenState = "MISSING";
function mount() {
  return render(<MemoryRouter><AuthProvider><Routes><Route element={<Layout />}><Route path="/" element={<>
    <p>Stored analytics</p><RepositoryAccessNote accessible={false} trafficPermissionMissing tokenState="OK" />
  </>} /></Route></Routes></AuthProvider></MemoryRouter>);
}
function expectHidden() {
  expect(screen.queryByText(TOKEN_MISSING)).not.toBeInTheDocument();
  expect(screen.queryByText(TOKEN_REJECTED)).not.toBeInTheDocument();
  expect(screen.queryByText(/Collection continues after/)).not.toBeInTheDocument();
  expect(screen.queryByText("No GitHub token")).not.toBeInTheDocument();
  expect(screen.queryByText(/Collected \d+ of/)).not.toBeInTheDocument();
  expect(screen.queryByText(/Collecting…|Auto-collect window|Outside auto-collect window|Auto-collect runs/)).not.toBeInTheDocument();
  expect(screen.queryByText(NO_ACCESS)).not.toBeInTheDocument();
  expect(screen.queryByText(TRAFFIC_PERMISSION)).not.toBeInTheDocument();
  expect(document.title).toBe("Repo Growth Monitor");
}
beforeEach(() => {
  api.mockReset(); loadMe.mockReset(); signOut.mockReset(); signOut.mockResolvedValue(undefined);
  tokenState = "MISSING"; loadMe.mockResolvedValue(anonymousMe);
  api.mockImplementation(async (path: string) => path.includes("/system/status")
    ? { tokenState, rateLimitReset: null, planner: { timezone: "UTC", from: "06:00", to: "18:00", open: true } }
    : { ...dashboardReady, repositories: dashboardReady.repositories.map(row => ({ ...row, collectionStatus: "RUNNING" })) });
});

describe("operational alerts by authentication", () => {
  it.each(banners)("hides %s notices and collection status for Anonymous", async (state) => {
    tokenState = state; const user = userEvent.setup(); mount();
    await screen.findByRole("button", { name: "Account menu" });
    await user.click(screen.getByRole("button", { name: "About data" }));
    expectHidden();
    expect(screen.getByText("Stored analytics")).toBeInTheDocument();
    expect(screen.getByText(/Last sync:/)).toBeInTheDocument();
    expect(screen.getByText(/Data is collected once per day/)).toBeInTheDocument();
  });

  it.each([...[registered, admin].flatMap(me => banners.map(([state, banner]) => ({ me, state, banner })))])(
    "preserves $state notices for authenticated users", async ({ me, state, banner }) => {
      loadMe.mockResolvedValue(me); tokenState = state; const user = userEvent.setup(); mount();
      expect(await screen.findByText(banner)).toBeInTheDocument();
      expect(screen.getByText(/Collected \d+ of/)).toBeInTheDocument();
      expect(screen.getByText(NO_ACCESS)).toBeInTheDocument();
      expect(screen.getByText(TRAFFIC_PERMISSION)).toBeInTheDocument();
      await user.click(screen.getByRole("button", { name: "About data" }));
      expect(screen.getByText(/Auto-collect runs every day/)).toBeInTheDocument();
      if (state === "MISSING") expect(document.title).toContain("no token");
    },
  );

  it("does not flash notices while authentication is unresolved", async () => {
    let resolveMe!: (me: typeof admin) => void;
    loadMe.mockImplementation(() => new Promise(resolve => { resolveMe = resolve; }));
    const user = userEvent.setup(); mount();
    await user.click(screen.getByRole("button", { name: "About data" }));
    expectHidden();
    await act(async () => resolveMe(admin));
    expect(await screen.findByText(TOKEN_MISSING)).toBeInTheDocument();
  });

  it("removes all operational details after sign-out even with About data open", async () => {
    loadMe.mockResolvedValue(admin); const user = userEvent.setup(); mount();
    await screen.findByText(TOKEN_MISSING);
    await user.click(screen.getByRole("button", { name: "About data" }));
    await user.click(screen.getByRole("button", { name: "Account menu" }));
    await user.click(screen.getByRole("menuitem", { name: "Sign out" }));
    await waitFor(expectHidden);
    expect(screen.getByText(/Data is collected once per day/)).toBeInTheDocument();
  });

  it("hides a dated rate-limit notice too", async () => {
    api.mockImplementation(async (path: string) => path.includes("/system/status")
      ? { tokenState: "RATE_LIMITED", rateLimitReset: "2026-11-02T12:10:17Z" }
      : dashboardReady);
    const user = userEvent.setup(); mount();
    await user.click(await screen.findByRole("button", { name: "About data" }));
    expectHidden();
  });
});
