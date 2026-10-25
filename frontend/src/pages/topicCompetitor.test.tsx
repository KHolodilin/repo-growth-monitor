import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { repository, topicHistory, topicResults } from "../test/fixtures";
import { TopicCompetitorCardPage } from "./TopicCompetitorCardPage";
import { TopicDetailsPage } from "./TopicDetailsPage";

const { api } = vi.hoisted(() => ({ api: vi.fn() }));
vi.mock("../lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../lib/api")>();
  return { ...actual, api };
});

const other = { ...repository, id: 8, name: "other", fullName: "acme/other" };

const card = {
  githubRepositoryId: 2,
  fullName: "big/outbox",
  description: "A production-ready neighbor for the transactional outbox pattern.",
  htmlUrl: "https://github.com/big/outbox",
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

function LocationProbe() {
  const location = useLocation();
  return <p>{`${location.pathname}${location.search}`}</p>;
}

function renderTopics(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/dashboard" element={<p>portfolio</p>} />
        <Route path="/missing" element={<TopicCompetitorCardPage />} />
        <Route path="/no-topic/:repositoryId/:githubRepositoryId" element={<TopicCompetitorCardPage />} />
        <Route path="/repositories/:id" element={<LocationProbe />} />
        <Route path="/repositories/:repositoryId/topics/:topic" element={<TopicDetailsPage />} />
        <Route
          path="/repositories/:repositoryId/topics/:topic/competitors/:githubRepositoryId"
          element={<TopicCompetitorCardPage />}
        />
      </Routes>
    </MemoryRouter>,
  );
}

function mockTopicApi(competitor: unknown = card) {
  api.mockImplementation((path: string) => {
    const url = String(path);
    if (url.includes("/repositories?refresh")) return Promise.resolve([repository, other]);
    if (url.endsWith("/repositories/13")) return Promise.resolve(repository);
    if (url.includes("/topics/") && url.includes("/history")) return Promise.resolve(topicHistory);
    if (url.includes("/topics/") && url.includes("/results")) return Promise.resolve(topicResults);
    if (url.includes("/competitors/")) return Promise.resolve(competitor);
    return Promise.resolve([]);
  });
}

describe("topic competitor card", () => {
  beforeEach(() => {
    document.cookie = "topics_visibility_scope_13=; path=/; max-age=0";
    api.mockReset();
  });

  it("opens another repository and the tracked repository on the same card", async () => {
    const user = userEvent.setup();
    mockTopicApi();
    renderTopics("/repositories/13/topics/outbox");

    expect(await screen.findByRole("link", { name: "big/outbox" })).toHaveAttribute(
      "href",
      "/repositories/13/topics/outbox/competitors/2",
    );
    expect(screen.getByRole("link", { name: repository.fullName })).toHaveAttribute(
      "href",
      "/repositories/13/topics/outbox/competitors/99",
    );
    expect(screen.getByRole("link", { name: "big/outbox" })).not.toHaveAttribute("target", "_blank");

    await user.click(screen.getByRole("link", { name: "big/outbox" }));
    expect(await screen.findByRole("heading", { name: "big/outbox" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Open on GitHub" })).toHaveAttribute("href", "https://github.com/big/outbox");
    expect(screen.getByRole("link", { name: "Open on GitHub" })).toHaveAttribute("target", "_blank");
    expect(screen.getByText(card.description)).toBeInTheDocument();
    expect(screen.getByText("kafka")).toBeInTheDocument();
    expect(screen.getByText("Discoverability 1/2")).toBeInTheDocument();
    expect(screen.getByText("Pass · Repository description exists")).toBeInTheDocument();
    expect(screen.getByText("Miss · License exists")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Stars and forks" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Topics Visibility" })).toHaveAttribute("href", "/repositories/13?tab=topics");
    expect(screen.getByRole("link", { name: "outbox" })).toHaveAttribute("href", "/repositories/13/topics/outbox");

    await user.click(screen.getByRole("link", { name: "outbox" }));
    expect(await screen.findByRole("heading", { name: "outbox" })).toBeInTheDocument();
  });

  it("keeps the language scope on the card and does not copy it to another repository", async () => {
    const user = userEvent.setup();
    mockTopicApi();
    renderTopics("/repositories/13/topics/outbox?scope=language");

    const result = await screen.findByRole("link", { name: "big/outbox" });
    expect(result).toHaveAttribute("href", "/repositories/13/topics/outbox/competitors/2?scope=language");
    await user.click(result);

    expect(await screen.findByRole("link", { name: "outbox" })).toHaveAttribute(
      "href",
      "/repositories/13/topics/outbox?scope=language",
    );
    expect(screen.getByRole("link", { name: "Topics Visibility" })).toHaveAttribute(
      "href",
      "/repositories/13?tab=topics&scope=language",
    );

    await user.click(screen.getByLabelText("Switch repository"));
    await user.click(await screen.findByRole("option", { name: "other" }));
    expect(await screen.findByText("/repositories/8?tab=topics")).toBeInTheDocument();
  });

  it("shows empty copy when the profile has no description or topics", async () => {
    mockTopicApi({ ...card, description: "  ", topics: [], points: [], discoverability: [] });
    renderTopics("/repositories/13/topics/outbox/competitors/2");
    expect(await screen.findByText("No description.")).toBeInTheDocument();
    expect(screen.getByText("No topics yet.")).toBeInTheDocument();
    expect(screen.getByText("Discoverability 0/0")).toBeInTheDocument();
  });

  it("shows a missing repository and ignores a cancelled request", async () => {
    api.mockImplementation((path: string) => {
      const url = String(path);
      if (url.includes("/competitors/")) return Promise.reject(new Error("Repository was not found in this topic"));
      if (url.endsWith("/repositories/13")) return Promise.resolve(repository);
      if (url.includes("/history")) return Promise.resolve(topicHistory);
      return Promise.resolve(null);
    });
    renderTopics("/repositories/13/topics/outbox/competitors/404");
    expect(await screen.findByText("Repository was not found in this topic")).toBeInTheDocument();
    cleanup();

    api.mockImplementation(() => Promise.reject("nope"));
    renderTopics("/repositories/13/topics/outbox/competitors/2");
    expect(await screen.findByText("Failed to load the competitor card")).toBeInTheDocument();
    cleanup();

    api.mockImplementation(() => new Promise(() => undefined));
    const pending = renderTopics("/repositories/13/topics/outbox/competitors/2");
    pending.unmount();

    api.mockReset();
    api.mockResolvedValue(card);
    renderTopics("/repositories/13/topics/outbox/competitors/0");
    expect(screen.getByText("Loading competitor card...")).toBeInTheDocument();
    await waitFor(() => expect(api).not.toHaveBeenCalled());
    cleanup();

    renderTopics("/no-topic/13/2");
    expect(screen.getByText("Loading competitor card...")).toBeInTheDocument();
    cleanup();

    renderTopics("/missing");
    expect(screen.getByText("Loading competitor card...")).toBeInTheDocument();
  });

  it("ignores a profile that arrives after the page closes", async () => {
    let settle: ((value: unknown) => void) | null = null;
    let fail: ((reason: unknown) => void) | null = null;
    api.mockImplementation((path: string) => {
      const url = String(path);
      if (url.includes("/competitors/2")) return new Promise((resolve) => { settle = resolve; });
      if (url.includes("/competitors/9")) return new Promise((_, reject) => { fail = reject; });
      if (url.endsWith("/repositories/13")) return Promise.resolve(repository);
      if (url.includes("/history")) return Promise.resolve(topicHistory);
      return Promise.resolve(null);
    });

    const first = renderTopics("/repositories/13/topics/outbox/competitors/2");
    await waitFor(() => expect(settle).not.toBeNull());
    first.unmount();
    settle?.(card);

    const second = renderTopics("/repositories/13/topics/outbox/competitors/9");
    await waitFor(() => expect(fail).not.toBeNull());
    second.unmount();
    fail?.(new Error("late"));
    await Promise.resolve();
  });
});
