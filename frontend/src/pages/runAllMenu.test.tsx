import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Link, MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { Layout } from "../components/Layout";
import { AuthProvider } from "../lib/AuthContext";
import { anonymousMe } from "../lib/auth";
import { dashboardReady, repository, searchHistory, topicHistory, traffic } from "../test/fixtures";
import { RepositoryDetailsPage } from "./RepositoryDetailsPage";

const { api, loadMe } = vi.hoisted(() => ({ api: vi.fn(), loadMe: vi.fn() }));
vi.mock("../lib/api", async (original) => ({ ...await original<typeof import("../lib/api")>(), api }));
vi.mock("../lib/auth", async (original) => ({ ...await original<typeof import("../lib/auth")>(), loadMe }));
const admin = { ...anonymousMe, authenticated: true, userId: "1", displayName: "Admin", roles: ["ADMIN"], actions: ["MUTATE"] };
const registered = { ...admin, roles: ["USER"], actions: [] };

let status = "SUCCESS";
let empty = false;
let run: () => Promise<unknown>;
function mount(tab = "search") {
  return render(<MemoryRouter initialEntries={[`/admin/repositories/13?tab=${tab}`]}><AuthProvider><Routes>
    <Route element={<Layout />}>
      <Route path="/admin/repositories/:id" element={<><Link to="/admin/repositories/8?tab=search">Next repository</Link><Link to="/admin/dashboard">Leave repository</Link><RepositoryDetailsPage /></>} />
      <Route path="/admin/dashboard" element={<p>Dashboard</p>} />
    </Route>
  </Routes></AuthProvider></MemoryRouter>);
}
async function openMenu(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole("button", { name: "Account menu" }));
}

beforeEach(() => {
  api.mockReset(); loadMe.mockReset(); loadMe.mockResolvedValue(admin);
  status = "SUCCESS"; empty = false; run = async () => ({});
  api.mockImplementation(async (path: string, options?: { method?: string }) => {
    if (options?.method === "POST") return run();
    if (path.startsWith("/api/v1/admin/dashboard")) return dashboardReady;
    if (/\/repositories\/\d+$/.test(path)) return { ...repository, id: path.endsWith("/8") ? 8 : 13 };
    if (path.includes("/traffic?")) return traffic;
    if (path.includes("/search-visibility")) return empty ? [] : [{ ...searchHistory, searchStatus: status }];
    if (path.includes("/topics-visibility")) return empty ? [] : [{ ...topicHistory, searchStatus: status }];
    return [];
  });
});

describe("repository Run all account action", () => {
  it("runs the active repository and topic scope, then disappears outside the relevant tabs", async () => {
    const user = userEvent.setup(); mount();
    await screen.findByRole("button", { name: "Add query" });
    expect(screen.queryByRole("button", { name: "Run all" })).not.toBeInTheDocument();
    await openMenu(user);
    await user.click(screen.getByRole("menuitem", { name: "Run all" }));
    await waitFor(() => expect(api).toHaveBeenCalledWith("/api/v1/admin/repositories/13/search-queries/run", { method: "POST" }));
    await user.click(screen.getByRole("button", { name: "Topics Visibility" }));
    await screen.findByRole("link", { name: "outbox" });
    expect(screen.queryByRole("button", { name: "Run all" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Java" }));
    await openMenu(user);
    await user.click(screen.getByRole("menuitem", { name: "Run all" }));
    await waitFor(() => expect(api).toHaveBeenCalledWith("/api/v1/admin/repositories/13/topic-watches/run?scope=language", { method: "POST" }));
    await user.click(screen.getByRole("link", { name: "Next repository" }));
    await screen.findByRole("button", { name: "Add query" });
    await openMenu(user);
    await user.click(screen.getByRole("menuitem", { name: "Run all" }));
    await waitFor(() => expect(api).toHaveBeenCalledWith("/api/v1/admin/repositories/8/search-queries/run", { method: "POST" }));
    await user.click(screen.getByRole("button", { name: "Traffic" }));
    await openMenu(user);
    expect(screen.queryByRole("menuitem", { name: /Run all|Running/ })).not.toBeInTheDocument();
    await user.click(screen.getByRole("link", { name: "Leave repository" }));
    expect(screen.queryByRole("menuitem", { name: /Run all|Running/ })).not.toBeInTheDocument();
  });

  it.each(["search", "topics"])("blocks a second %s run and preserves API errors", async (tab) => {
    const user = userEvent.setup();
    let rejectRun!: (reason: Error) => void;
    run = () => new Promise((_, reject) => { rejectRun = reject; });
    mount(tab);
    await screen.findByRole("link", { name: tab === "search" ? searchHistory.query.name : "outbox" });
    await openMenu(user);
    await user.click(screen.getByRole("menuitem", { name: "Run all" }));
    await openMenu(user);
    const busy = screen.getByRole("menuitem", { name: "Running..." });
    expect(busy).toBeDisabled(); await user.click(busy);
    expect(api.mock.calls.filter(([, options]) => options?.method === "POST")).toHaveLength(1);
    rejectRun(new Error("batch failed"));
    expect(await screen.findByText("batch failed")).toBeInTheDocument();
    if (tab === "search") expect(await screen.findByRole("menuitem", { name: "Run all" })).toBeEnabled();
    else await waitFor(() => expect(screen.queryByRole("menuitem", { name: /Run all|Running/ })).not.toBeInTheDocument());
  });

  it.each(["search", "topics"])("disables empty and queued %s batches", async (tab) => {
    const user = userEvent.setup(); empty = true; const view = mount(tab);
    await screen.findByRole("heading", { name: tab === "search" ? "Search Queries" : "Topics" });
    await openMenu(user); expect(screen.getByRole("menuitem", { name: "Run all" })).toBeDisabled();
    view.unmount(); empty = false; status = "READY"; mount(tab);
    await screen.findByRole("link", { name: tab === "search" ? searchHistory.query.name : "outbox" });
    await openMenu(user); expect(screen.getByRole("menuitem", { name: "Running..." })).toBeDisabled();
  });

  it.each([
    ["anonymous", anonymousMe, "search"], ["anonymous", anonymousMe, "topics"],
    ["registered", registered, "search"], ["registered", registered, "topics"],
  ] as const)("hides actions for %s without mutation access", async (_role, me, tab) => {
    loadMe.mockResolvedValue(me); const user = userEvent.setup(); mount(tab);
    await screen.findByRole("link", { name: tab === "search" ? searchHistory.query.name : "outbox" });
    expect(screen.queryByRole("button", { name: "Run all" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Add query" })).not.toBeInTheDocument();
    await openMenu(user);
    expect(screen.queryByRole("menuitem", { name: /Run all|Running/ })).not.toBeInTheDocument();
  });
});
