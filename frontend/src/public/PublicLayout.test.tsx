import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { AuthProvider } from "../lib/AuthContext";
import { anonymousMe } from "../lib/auth";
import { useDashboardView } from "../lib/dashboardView";
import { PublicLayout } from "./PublicLayout";

const { api, loadMe } = vi.hoisted(() => ({ api: vi.fn(), loadMe: vi.fn() }));
vi.mock("../lib/api", async (original) => ({
  ...(await original<typeof import("../lib/api")>()),
  api,
}));
vi.mock("../lib/auth", async (original) => ({
  ...(await original<typeof import("../lib/auth")>()),
  loadMe,
}));

function mount(path: string) {
  cleanup();
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AuthProvider>
        <Routes>
          <Route element={<PublicLayout />}>
            <Route path="/" element={<p>home</p>} />
            <Route path="/:owner/:repository" element={<p>repo</p>} />
            <Route path="/admin/*" element={<p>admin</p>} />
          </Route>
        </Routes>
      </AuthProvider>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  api.mockReset();
  loadMe.mockReset();
  loadMe.mockResolvedValue(anonymousMe);
});
afterEach(() => cleanup());

describe("PublicLayout", () => {
  it("matches the admin header geometry for anonymous sessions", () => {
    mount("/");
    const brand = screen.getByRole("link", { name: "Repo Growth Monitor" });
    expect(brand.className).toContain("gap-2.5");
    expect(brand.querySelector("img")?.className).toContain("h-8");
    expect(brand).toHaveTextContent("Repo Growth Monitor");
    const signIn = screen.getByRole("link", { name: "Sign in" });
    expect(signIn).toHaveAttribute("href", "/login");
    expect(signIn.className).toContain("h-8");
    expect(signIn.className).toContain("w-8");
    expect(signIn.className).not.toContain("border");
    expect(signIn.className).not.toContain("min-h-11");
  });

  it("hides collection-incomplete markers for anonymous and keeps them for signed-in users", async () => {
    api.mockResolvedValue({ freshness: {} });
    function Probe() {
      const view = useDashboardView();
      return <p>{`public=${String(view.public)} expected=${String(view.collectionExpected)}`}</p>;
    }
    function mountProbe() {
      return render(
        <MemoryRouter initialEntries={["/Alpha/project"]}>
          <AuthProvider>
            <Routes>
              <Route element={<PublicLayout />}>
                <Route path="/:owner/:repository" element={<Probe />} />
              </Route>
            </Routes>
          </AuthProvider>
        </MemoryRouter>,
      );
    }
    mountProbe();
    expect(await screen.findByText("public=true expected=false")).toBeInTheDocument();

    cleanup();
    loadMe.mockResolvedValue({
      ...anonymousMe,
      authenticated: true,
      userId: "1",
      displayName: "User",
      roles: ["USER"],
    });
    mountProbe();
    expect(await screen.findByText("public=true expected=true")).toBeInTheDocument();
  });

  it("toggles About data on the public home footer", async () => {
    const user = userEvent.setup();
    mount("/");
    expect(screen.getByText(/Public dashboards use saved snapshots/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "About data" }));
    expect(screen.getByText(/Data is collected once per day/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "GitHub" })).toHaveAttribute(
      "href",
      "https://github.com/KHolodilin/repo-growth-monitor",
    );
  });

  it("loads last sync for owner pages and clears it for admin routes", async () => {
    const user = userEvent.setup();
    api.mockResolvedValueOnce({
      freshness: { lastSuccessfulSnapshotAt: "2026-11-01T12:00:00Z" },
    });
    mount("/Alpha/project");
    await waitFor(() => expect(screen.getByText(/Last sync:/)).toBeInTheDocument());
    await user.click(screen.getByRole("button", { name: "About data" }));
    expect(screen.getByText(/Data is collected once per day/)).toBeInTheDocument();

    api.mockRejectedValueOnce(new Error("gone"));
    mount("/Alpha/project");
    await waitFor(() => expect(screen.getByText("No successful sync yet")).toBeInTheDocument());

    mount("/admin/repositories");
    expect(screen.getByText("No successful sync yet")).toBeInTheDocument();
    expect(api.mock.calls.every(([url]) => !String(url).includes("/admin"))).toBe(true);
  });

  it("ignores aborted sync success and failure after unmount", async () => {
    let finish!: (value: unknown) => void;
    api.mockImplementation(() => new Promise((resolve) => { finish = resolve; }));
    const first = mount("/Alpha/project");
    first.unmount();
    finish({ freshness: { lastSuccessfulSnapshotAt: "2026-11-01T12:00:00Z" } });

    let fail!: (error: Error) => void;
    api.mockImplementation(() => new Promise((_, reject) => { fail = reject; }));
    const second = mount("/Alpha/project");
    second.unmount();
    fail(new Error("cancelled"));
    expect(api).toHaveBeenCalled();
  });
});
