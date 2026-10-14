import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";
import { repository, searchHistory } from "../test/fixtures";
import { CompetitorCardPage } from "./CompetitorCardPage";

const { api } = vi.hoisted(() => ({ api: vi.fn() }));
vi.mock("../lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../lib/api")>();
  return { ...actual, api };
});

const card = {
  githubRepositoryId: 1,
  fullName: "other/repo",
  description: "A production-ready neighbor for the transactional outbox pattern.",
  htmlUrl: "https://github.com/other/repo",
  topics: ["kafka", "outbox"],
  discoverability: [
    { label: "Repository description exists", passed: true },
    { label: "License exists", passed: false },
  ],
  points: [
    { date: "2026-09-22", stars: 10, forks: 1 },
    { date: "2026-09-23", stars: null, forks: null },
  ],
};

function renderCard(path = "/repositories/13/search-queries/5/competitors/1") {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route
          path="/repositories/:repositoryId/search-queries/:queryId/competitors/:githubRepositoryId"
          element={<CompetitorCardPage />}
        />
        <Route path="/missing" element={<CompetitorCardPage />} />
        <Route path="/repositories/:repositoryId" element={<p>repository</p>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe("competitor card", () => {
  it("shows the cached profile, checks, and a gap in the stored counts", async () => {
    const user = userEvent.setup();
    api.mockImplementation((path: string) => {
      const url = String(path);
      if (url.endsWith("/repositories/13")) return Promise.resolve(repository);
      if (url.includes("/repositories?")) return Promise.resolve([repository]);
      if (url.endsWith("/history")) return Promise.resolve(searchHistory);
      if (url.endsWith("/competitors/1")) return Promise.resolve(card);
      return Promise.resolve(null);
    });

    renderCard();

    expect(await screen.findByRole("heading", { name: "other/repo" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Open on GitHub" })).toHaveAttribute("href", "https://github.com/other/repo");
    expect(screen.getByText(card.description)).toBeInTheDocument();
    expect(screen.getByText("kafka")).toBeInTheDocument();
    expect(screen.getByText("Discoverability 1/2")).toBeInTheDocument();
    expect(screen.getByText("Pass · Repository description exists")).toBeInTheDocument();
    expect(screen.getByText("Miss · License exists")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Stars and forks" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: searchHistory.query.query })).toHaveAttribute(
      "href",
      "/repositories/13/search-queries/5",
    );
    await user.click(screen.getByLabelText("Switch repository"));
    await user.click(await screen.findByRole("option", { name: repository.name }));
  });

  it("shows empty copy when the profile has no description, topics, or days", async () => {
    api.mockImplementation((path: string) => {
      const url = String(path);
      if (url.endsWith("/repositories/13")) return Promise.resolve(repository);
      if (url.endsWith("/history")) return Promise.resolve(searchHistory);
      if (url.endsWith("/competitors/1")) {
        return Promise.resolve({ ...card, description: "  ", topics: [], points: [], discoverability: [] });
      }
      return Promise.resolve(null);
    });

    renderCard();

    expect(await screen.findByText("No description.")).toBeInTheDocument();
    expect(screen.getByText("No topics yet.")).toBeInTheDocument();
    expect(screen.getByText("Discoverability 0/0")).toBeInTheDocument();
  });

  it("shows the load error and ignores a cancelled request", async () => {
    api.mockImplementation(() => Promise.reject(new Error("github down")));
    renderCard();
    expect(await screen.findByText("github down")).toBeInTheDocument();
    cleanup();

    api.mockImplementation(() => Promise.reject("nope"));
    renderCard();
    expect(await screen.findByText("Failed to load the competitor card")).toBeInTheDocument();
    cleanup();

    api.mockImplementation(() => new Promise(() => undefined));
    const pending = renderCard();
    pending.unmount();

    const missing = renderCard("/missing");
    expect(screen.getByText("Loading competitor card...")).toBeInTheDocument();
    missing.unmount();
  });
});
