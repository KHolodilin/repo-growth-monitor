import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { PublicLayout } from "./PublicLayout";

const { api } = vi.hoisted(() => ({ api: vi.fn() }));
vi.mock("../lib/api", async (original) => ({
  ...(await original<typeof import("../lib/api")>()),
  api,
}));

function mount(path: string) {
  cleanup();
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route element={<PublicLayout />}>
          <Route path="/" element={<p>home</p>} />
          <Route path="/:owner/:repository" element={<p>repo</p>} />
          <Route path="/admin/*" element={<p>admin</p>} />
        </Route>
      </Routes>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  api.mockReset();
});
afterEach(() => cleanup());

describe("PublicLayout", () => {
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
