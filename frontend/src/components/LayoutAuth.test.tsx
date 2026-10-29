import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";
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

describe("Layout auth chrome", () => {
  it("shows Sign in for guests and Sign out for admins", async () => {
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
    expect(await screen.findByRole("link", { name: "Sign in" })).toBeInTheDocument();
    expect(screen.getByText(/Sign in as an admin/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Collect/ })).toBeDisabled();

    loadMe.mockResolvedValue({
      ...anonymousMe,
      authenticated: true,
      userId: "1",
      displayName: "Mock Admin",
      roles: ["USER", "ADMIN"],
      actions: ["MUTATE"],
    });
    // remount with admin session
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
    expect(await screen.findByText("Mock Admin")).toBeInTheDocument();
    signOut.mockResolvedValue(undefined);
    await user.click(screen.getByRole("button", { name: "Sign out" }));
    await waitFor(() => expect(signOut).toHaveBeenCalled());
  });
});
