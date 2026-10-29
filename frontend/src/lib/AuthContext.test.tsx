import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { AuthProvider, useAuth } from "./AuthContext";
import { anonymousMe } from "./auth";

const { loadMe, signOut } = vi.hoisted(() => ({
  loadMe: vi.fn(),
  signOut: vi.fn(),
}));

vi.mock("./auth", async (importOriginal) => {
  const actual = await importOriginal<typeof import("./auth")>();
  return { ...actual, loadMe, signOut };
});

function Probe() {
  const { me, loading, logout, refresh } = useAuth();
  return (
    <div>
      <span data-testid="loading">{String(loading)}</span>
      <span data-testid="name">{me.displayName ?? "anon"}</span>
      <span data-testid="auth">{String(me.authenticated)}</span>
      <button type="button" onClick={() => void logout()}>
        out
      </button>
      <button type="button" onClick={() => void refresh()}>
        refresh
      </button>
    </div>
  );
}

describe("AuthProvider", () => {
  it("loads me and supports logout/refresh", async () => {
    const user = userEvent.setup();
    loadMe.mockResolvedValue({
      ...anonymousMe,
      authenticated: true,
      userId: "9",
      displayName: "Ada",
      roles: ["USER", "ADMIN"],
      actions: ["MUTATE"],
    });
    signOut.mockResolvedValue(undefined);
    render(
      <AuthProvider>
        <Probe />
      </AuthProvider>,
    );
    await waitFor(() => expect(screen.getByTestId("name")).toHaveTextContent("Ada"));
    expect(screen.getByTestId("auth")).toHaveTextContent("true");
    expect(screen.getByTestId("loading")).toHaveTextContent("false");

    loadMe.mockRejectedValueOnce(new Error("boom"));
    await user.click(screen.getByRole("button", { name: "refresh" }));
    await waitFor(() => expect(screen.getByTestId("auth")).toHaveTextContent("false"));

    loadMe.mockResolvedValue({
      ...anonymousMe,
      authenticated: true,
      displayName: "Ada",
      userId: "9",
      roles: ["USER"],
      actions: [],
    });
    await user.click(screen.getByRole("button", { name: "refresh" }));
    await waitFor(() => expect(screen.getByTestId("name")).toHaveTextContent("Ada"));
    await user.click(screen.getByRole("button", { name: "out" }));
    await waitFor(() => expect(screen.getByTestId("auth")).toHaveTextContent("false"));
    expect(signOut).toHaveBeenCalled();
  });

  it("falls back to anonymous when first load fails", async () => {
    loadMe.mockRejectedValueOnce(new Error("offline"));
    render(
      <AuthProvider>
        <Probe />
      </AuthProvider>,
    );
    await waitFor(() => expect(screen.getByTestId("loading")).toHaveTextContent("false"));
    expect(screen.getByTestId("auth")).toHaveTextContent("false");
  });
});
