import { render, screen } from "@testing-library/react";
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

const tracked = { ...repository, accessible: true, trafficPermissionMissing: false, visibility: "PUBLIC" };
const privateRepo = {
  ...repository,
  id: 8,
  name: "secret",
  fullName: "KHolodilin/secret",
  visibility: "PRIVATE",
  trackingEnabled: true,
  accessible: true,
};

describe("repositories auth gating", () => {
  beforeEach(() => {
    sessionStorage.clear();
    api.mockReset();
    loadMe.mockReset();
    loadMe.mockResolvedValue(anonymousMe);
  });

  it("hides private repositories and tracking controls for guests", async () => {
    api.mockImplementation((path: string) => {
      if (String(path).includes("/system/status")) {
        return { tokenState: "OK", rateLimitReset: null };
      }
      return [tracked, privateRepo];
    });
    render(
      <MemoryRouter>
        <AuthProvider>
          <RepositoriesPage />
        </AuthProvider>
      </MemoryRouter>,
    );
    expect(await screen.findByText(tracked.fullName)).toBeInTheDocument();
    expect(screen.queryByText(privateRepo.fullName)).not.toBeInTheDocument();
    expect(screen.queryByRole("checkbox")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Refresh from GitHub/ })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Stop tracking/ })).not.toBeInTheDocument();
  });
});
