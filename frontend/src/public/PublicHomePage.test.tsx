import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import App from "../App";
import { anonymousMe } from "../lib/auth";
import { readLandingDirectoryPrefs, writeLandingDirectoryPrefs } from "../lib/landingPrefs";

const { api, loadMe } = vi.hoisted(() => ({ api: vi.fn(), loadMe: vi.fn() }));

vi.mock("../lib/api", async (original) => ({ ...(await original<typeof import("../lib/api")>()), api }));
vi.mock("../lib/auth", async (original) => ({ ...(await original<typeof import("../lib/auth")>()), loadMe }));

const owners = [
  { id: "2", login: "Beta", canonicalPath: "/Beta", repositories: 1, stars: 40, forks: 4, watchers: 2, contributors: 5 },
  { id: "1", login: "Alpha", canonicalPath: "/Alpha", repositories: 2, stars: 30, forks: 3, watchers: 1, contributors: null },
];

function page(path = "/?sort=stars&order=desc&limit=25") {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <App />
    </MemoryRouter>,
  );
}

beforeEach(() => {
  localStorage.clear();
  api.mockReset();
  loadMe.mockReset();
  loadMe.mockResolvedValue(anonymousMe);
  api.mockImplementation((path: string) => {
    if (!path.startsWith("/api/v1/public/owners?")) {
      throw new Error("Unexpected private request: " + path);
    }
    const url = new URL(path, "http://local.test");
    const sort = url.searchParams.get("sort") ?? "stars";
    const order = url.searchParams.get("order") ?? "desc";
    const after = Number(url.searchParams.get("after") ?? "0");
    const limit = Number(url.searchParams.get("limit") ?? "25");
    const key = sort === "owner" ? "login" : sort;
    const sorted = [...owners].sort((left, right) => {
      const leftValue = left[key as keyof (typeof owners)[number]] as string | number | null;
      const rightValue = right[key as keyof (typeof owners)[number]] as string | number | null;
      if (leftValue == null && rightValue == null) return Number(left.id) - Number(right.id);
      if (leftValue == null) return 1;
      if (rightValue == null) return -1;
      if (leftValue === rightValue) return Number(left.id) - Number(right.id);
      const compared = leftValue < rightValue ? -1 : 1;
      return order === "asc" ? compared : -compared;
    });
    const slice = sorted.slice(after, after + limit);
    return Promise.resolve({
      items: slice,
      nextCursor: after + limit < sorted.length ? String(after + limit) : null,
    });
  });
});

describe("public homepage owners directory", () => {
  it("renders the approved homepage structure and real owners table", async () => {
    const user = userEvent.setup();
    const scrollIntoView = vi.fn();
    Object.defineProperty(HTMLElement.prototype, "scrollIntoView", { configurable: true, value: scrollIntoView });
    page();
    expect(await screen.findByRole("heading", { name: "Understand how your open source projects grow" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Explore dashboards" }));
    expect(scrollIntoView).toHaveBeenCalled();
    expect(screen.getByRole("heading", { name: "Traffic history" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Community stats" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Search visibility" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Topic rankings" })).toBeInTheDocument();
    const table = await screen.findByRole("table", { name: "Published owners directory" });
    expect(within(table).getByRole("link", { name: "Beta" })).toHaveAttribute("href", "/Beta");
    expect(within(table).getByRole("link", { name: "Alpha" })).toHaveAttribute("href", "/Alpha");
    expect(within(table).getByText("—")).toBeInTheDocument();
    expect(screen.queryByText(/avatar|github\.com|Top 5/i)).not.toBeInTheDocument();
    expect(api.mock.calls.every(([request]) => String(request).startsWith("/api/v1/public/"))).toBe(true);
  });

  it("persists sort in the URL and landing preference namespace", async () => {
    const user = userEvent.setup();
    page("/?sort=stars&order=desc&limit=10");
    const table = await screen.findByRole("table", { name: "Published owners directory" });
    expect(await within(table).findByRole("link", { name: "Beta" })).toBeInTheDocument();
    await user.click(within(table).getByRole("button", { name: /Owner/ }));
    await waitFor(() => expect(api.mock.calls.at(-1)?.[0]).toContain("sort=owner"));
    expect(api.mock.calls.at(-1)?.[0]).toContain("order=asc");
    expect(readLandingDirectoryPrefs()).toEqual({ sort: "owner", order: "asc", limit: 10 });
  });

  it("pages with 10/25/50 and recovers from empty or failed loads", async () => {
    const user = userEvent.setup();
    writeLandingDirectoryPrefs({ sort: "stars", order: "desc", limit: 10 });
    page("/?sort=stars&order=desc&limit=10");
    expect(await screen.findByRole("link", { name: "Beta" })).toBeInTheDocument();
    await user.selectOptions(screen.getByLabelText("Owners page size"), "50");
    await waitFor(() => expect(api.mock.calls.at(-1)?.[0]).toContain("limit=50"));
    api.mockResolvedValueOnce({ items: [], nextCursor: null });
    await user.click(screen.getByRole("button", { name: /^Stars/ }));
    expect(await screen.findByText("No published owners yet.")).toBeInTheDocument();
    api.mockRejectedValueOnce(new Error("Directory unavailable"));
    await user.click(screen.getByRole("button", { name: /^Owner/ }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Directory unavailable");
    await user.click(screen.getByRole("button", { name: "Retry" }));
    expect(await screen.findByRole("link", { name: "Beta" })).toBeInTheDocument();
  });

  it("keeps Next/Previous tied to the server cursor and Back-friendly URL state", async () => {
    const user = userEvent.setup();
    api.mockImplementation((path: string) => {
      if (!String(path).startsWith("/api/v1/public/owners?")) throw new Error(String(path));
      const url = new URL(String(path), "http://local.test");
      const after = Number(url.searchParams.get("after") ?? "0");
      const item = owners[after];
      return Promise.resolve({ items: item ? [item] : [], nextCursor: after + 1 < owners.length ? String(after + 1) : null });
    });
    page("/?sort=stars&order=desc&limit=1");
    expect(await screen.findByRole("link", { name: "Beta" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Next" }));
    expect(await screen.findByRole("link", { name: "Alpha" })).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Beta" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Previous" }));
    expect(await screen.findByRole("link", { name: "Beta" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /^Stars/ })).toHaveAttribute("aria-sort", "descending");
  });
});
