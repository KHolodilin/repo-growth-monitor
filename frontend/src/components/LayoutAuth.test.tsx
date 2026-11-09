import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";
import { ApiError } from "../lib/api";
import { AuthProvider } from "../lib/AuthContext";
import { anonymousMe } from "../lib/auth";
import { dashboardReady } from "../test/fixtures";
import { Layout } from "./Layout";

const { api, loadMe, signOut } = vi.hoisted(() => ({
  api: vi.fn(),
  loadMe: vi.fn(),
  signOut: vi.fn(),
}));

vi.mock("../lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../lib/api")>();
  return { ...actual, api };
});

vi.mock("../lib/auth", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../lib/auth")>();
  return { ...actual, loadMe, signOut };
});

const adminMe = {
  ...anonymousMe,
  authenticated: true,
  userId: "1",
  displayName: "Mock Admin",
  roles: ["USER", "ADMIN"],
  actions: ["MUTATE"],
};

const registeredMe = {
  ...anonymousMe,
  authenticated: true,
  userId: "2",
  displayName: "Mock User",
  roles: ["USER"],
  actions: [],
};

describe("Layout auth chrome", () => {
  it("keeps only the account menu in the header and roles the menu items", async () => {
    const user = userEvent.setup();
    api.mockResolvedValue(dashboardReady);
    loadMe.mockResolvedValue(anonymousMe);
    render(
      <MemoryRouter>
        <AuthProvider>
          <Routes>
            <Route element={<Layout />}>
              <Route path="/" element={<p>home</p>} />
            </Route>
          </Routes>
        </AuthProvider>
      </MemoryRouter>,
    );
    expect(await screen.findByRole("button", { name: "Account menu" })).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Dashboard" })).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Repositories" })).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Sign in" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Collect/ })).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Account menu" }));
    expect(screen.getByRole("menuitem", { name: "Sign in" })).toBeInTheDocument();
    expect(screen.queryByRole("menuitem", { name: "Repositories" })).not.toBeInTheDocument();
    expect(screen.queryByRole("menuitem", { name: /Collect/ })).not.toBeInTheDocument();
    cleanup();

    loadMe.mockResolvedValue(registeredMe);
    render(
      <MemoryRouter>
        <AuthProvider>
          <Routes>
            <Route element={<Layout />}>
              <Route path="/" element={<p>home</p>} />
              <Route path="/admin/repositories" element={<p>repos</p>} />
            </Route>
          </Routes>
        </AuthProvider>
      </MemoryRouter>,
    );
    await user.click(await screen.findByRole("button", { name: "Account menu" }));
    expect(screen.getByText("Mock User")).toBeInTheDocument();
    expect(screen.getByRole("menuitem", { name: "Repositories" })).toBeInTheDocument();
    expect(screen.getByRole("menuitem", { name: "Sign out" })).toBeInTheDocument();
    expect(screen.queryByRole("menuitem", { name: /Collect/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("menuitem", { name: "Sign in" })).not.toBeInTheDocument();
    cleanup();

    loadMe.mockResolvedValue(adminMe);
    render(
      <MemoryRouter>
        <AuthProvider>
          <Routes>
            <Route element={<Layout />}>
              <Route path="/" element={<p>home</p>} />
            </Route>
          </Routes>
        </AuthProvider>
      </MemoryRouter>,
    );
    expect(await screen.findByRole("button", { name: "Account menu" })).toBeInTheDocument();
    expect(screen.queryByText("Mock Admin")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Collect/ })).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Account menu" }));
    expect(screen.getByText("Mock Admin")).toBeInTheDocument();
    expect(screen.getByRole("menuitem", { name: "Repositories" })).toBeInTheDocument();
    expect(screen.getByRole("menuitem", { name: /Collect/ })).toBeInTheDocument();
    signOut.mockResolvedValue(undefined);
    await user.click(screen.getByRole("menuitem", { name: "Sign out" }));
    await waitFor(() => expect(signOut).toHaveBeenCalled());
  });

  it("shows session expired when collect returns 401", async () => {
    const user = userEvent.setup();
    api.mockImplementation((path: string, init?: RequestInit) => {
      if (init?.method === "POST" && String(path).includes("/collect")) {
        return Promise.reject(new ApiError(401, "Authentication required"));
      }
      return Promise.resolve(dashboardReady);
    });
    loadMe.mockResolvedValue(adminMe);
    render(
      <MemoryRouter>
        <AuthProvider>
          <Routes>
            <Route element={<Layout />}>
              <Route path="/" element={<p>home</p>} />
            </Route>
          </Routes>
        </AuthProvider>
      </MemoryRouter>,
    );
    expect(await screen.findByRole("button", { name: "Account menu" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Account menu" }));
    await user.click(screen.getByRole("menuitem", { name: /Collect/ }));
    expect(await screen.findByText(/Session expired/)).toBeInTheDocument();
  });

  it("closes the account menu on Escape", async () => {
    const user = userEvent.setup();
    api.mockResolvedValue(dashboardReady);
    loadMe.mockResolvedValue(adminMe);
    render(
      <MemoryRouter>
        <AuthProvider>
          <Routes>
            <Route element={<Layout />}>
              <Route path="/" element={<p>home</p>} />
            </Route>
          </Routes>
        </AuthProvider>
      </MemoryRouter>,
    );
    await user.click(await screen.findByRole("button", { name: "Account menu" }));
    expect(screen.getByText("Mock Admin")).toBeInTheDocument();
    await user.keyboard("{Escape}");
    expect(screen.queryByText("Mock Admin")).not.toBeInTheDocument();
  });

  it("closes the guest menu after Sign in and when clicking outside", async () => {
    const user = userEvent.setup();
    api.mockResolvedValue(dashboardReady);
    loadMe.mockResolvedValue(anonymousMe);
    render(
      <MemoryRouter>
        <AuthProvider>
          <Routes>
            <Route element={<Layout />}>
              <Route path="/" element={<p>home</p>} />
            </Route>
          </Routes>
        </AuthProvider>
      </MemoryRouter>,
    );
    await user.click(await screen.findByRole("button", { name: "Account menu" }));
    expect(screen.getByRole("menuitem", { name: "Sign in" })).toBeInTheDocument();
    await user.click(screen.getByRole("menuitem", { name: "Sign in" }));
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Account menu" }));
    expect(screen.getByRole("menuitem", { name: "Sign in" })).toBeInTheDocument();
    await user.click(screen.getByText("home"));
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
  });

  it("shows the account id under a different display name", async () => {
    const user = userEvent.setup();
    api.mockResolvedValue(dashboardReady);
    loadMe.mockResolvedValue(adminMe);
    render(
      <MemoryRouter>
        <AuthProvider>
          <Routes>
            <Route element={<Layout />}>
              <Route path="/" element={<p>home</p>} />
            </Route>
          </Routes>
        </AuthProvider>
      </MemoryRouter>,
    );
    await user.click(await screen.findByRole("button", { name: "Account menu" }));
    expect(screen.getByText("Mock Admin")).toBeInTheDocument();
    expect(screen.getByText("1")).toBeInTheDocument();
  });
});
