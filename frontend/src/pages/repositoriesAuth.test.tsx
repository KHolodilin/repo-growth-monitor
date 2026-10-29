import { fireEvent, render, screen, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { AuthProvider } from "../lib/AuthContext";
import { anonymousMe } from "../lib/auth";
import { repository } from "../test/fixtures";
import { RepositoriesPage } from "./RepositoriesPage";

const { api, loadMe } = vi.hoisted(() => ({ api: vi.fn(), loadMe: vi.fn() }));

vi.mock("../lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../lib/api")>();
  return { ...actual, api };
});

vi.mock("../lib/auth", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../lib/auth")>();
  return { ...actual, loadMe };
});

const tracked = { ...repository, accessible: true, trafficPermissionMissing: false };

describe("repositories auth gating", () => {
  beforeEach(() => {
    sessionStorage.clear();
    api.mockReset();
    loadMe.mockReset();
    loadMe.mockResolvedValue(anonymousMe);
  });

  it("blocks tracking changes for guests", async () => {
    api.mockImplementation((path: string) => {
      if (String(path).includes("/system/status")) {
        return { tokenState: "OK", rateLimitReset: null };
      }
      return [tracked];
    });
    render(
      <MemoryRouter>
        <AuthProvider>
          <RepositoriesPage />
        </AuthProvider>
      </MemoryRouter>,
    );
    expect(await screen.findByText(tracked.fullName)).toBeInTheDocument();
    const row = screen.getByText(tracked.fullName).closest("label");
    const checkbox = within(row as HTMLElement).getByRole("checkbox");
    expect(checkbox).toBeDisabled();
    fireEvent.click(checkbox);
    expect(await screen.findByText(/Sign in as an admin|Admin role required/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: /Stop tracking/ }));
    expect(screen.getByText(/Sign in as an admin|Admin role required/)).toBeInTheDocument();
  });
});
