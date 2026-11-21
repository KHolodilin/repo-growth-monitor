import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { repository, topicHistory, topicResults } from "../test/fixtures";
import { isPublicResolve, publicResolveResponse } from "../test/repoRoutes";
import { TopicCompetitorCardPage } from "./TopicCompetitorCardPage";
import { TopicDetailsPage } from "./TopicDetailsPage";

const { api } = vi.hoisted(() => ({ api: vi.fn() }));
vi.mock("../lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../lib/api")>();
  return { ...actual, api };
});

const other = { ...repository, id: 8, name: "other", fullName: "KHolodilin/other", owner: { ...repository.owner, login: "KHolodilin" } };
const base = `/${repository.owner.login}/${repository.name}`;

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
        <Route path="/admin/dashboard" element={<p>portfolio</p>} />
        <Route path="/missing" element={<TopicCompetitorCardPage />} />
        <Route path="/no-topic/:owner/:repository/:githubRepositoryId" element={<TopicCompetitorCardPage />} />
        <Route path="/:owner/:repository" element={<LocationProbe />} />
        <Route path="/:owner/:repository/topics/:topic" element={<TopicDetailsPage />} />
        <Route
          path="/:owner/:repository/topics/:topic/competitors/:githubRepositoryId"
          element={<TopicCompetitorCardPage />}
        />
      </Routes>
    </MemoryRouter>,
  );
}

function mockTopicApi(competitor: unknown = card) {
  api.mockImplementation((path: string) => {
    const url = String(path);
    if (isPublicResolve(url)) {
      const id = url.toLowerCase().includes("other") ? other.id : repository.id;
      return Promise.resolve(publicResolveResponse(id));
    }
    if (url.includes("/admin/repositories?refresh") || url.includes("/admin/repositories?refresh=false")) {
      return Promise.resolve([repository, other]);
    }
    if (url.includes("/admin/repositories?")) return Promise.resolve([repository, other]);
    if (url.endsWith("/admin/repositories/13")) return Promise.resolve(repository);
    if (url.endsWith("/admin/repositories/8")) return Promise.resolve(other);
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
    renderTopics(`${base}/topics/outbox`);

    expect(await screen.findByRole("link", { name: "big/outbox" })).toHaveAttribute(
      "href",
      `${base}/topics/outbox/competitors/2`,
    );
    expect(screen.getByRole("link", { name: repository.fullName })).toHaveAttribute(
      "href",
      `${base}/topics/outbox/competitors/99`,
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
    expect(screen.getByRole("link", { name: "Topics Visibility" })).toHaveAttribute("href", `${base}?tab=topics`);
    expect(screen.getByRole("link", { name: "outbox" })).toHaveAttribute("href", `${base}/topics/outbox`);

    await user.click(screen.getByRole("link", { name: "outbox" }));
    expect(await screen.findByRole("heading", { name: "outbox" })).toBeInTheDocument();
  });

  it("keeps the language scope on the card and does not copy it to another repository", async () => {
    const user = userEvent.setup();
    mockTopicApi();
    renderTopics(`${base}/topics/outbox?scope=language`);

    const result = await screen.findByRole("link", { name: "big/outbox" });
    expect(result).toHaveAttribute("href", `${base}/topics/outbox/competitors/2?scope=language`);
    await user.click(result);

    expect(await screen.findByRole("link", { name: "outbox" })).toHaveAttribute(
      "href",
      `${base}/topics/outbox?scope=language`,
    );
    expect(screen.getByRole("link", { name: "Topics Visibility" })).toHaveAttribute(
      "href",
      `${base}?tab=topics&scope=language`,
    );

    await user.click(screen.getByLabelText("Switch repository"));
    await user.click(await screen.findByRole("option", { name: "other" }));
    expect(await screen.findByText("/KHolodilin/other?tab=topics")).toBeInTheDocument();
  });

  it("shows empty copy when the profile has no description or topics", async () => {
    mockTopicApi({ ...card, description: "  ", topics: [], points: [], discoverability: [] });
    renderTopics(`${base}/topics/outbox/competitors/2`);
    expect(await screen.findByText("No description.")).toBeInTheDocument();
    expect(screen.getByText("No topics yet.")).toBeInTheDocument();
    expect(screen.getByText("Discoverability 0/0")).toBeInTheDocument();
  });

  it("shows a missing repository and ignores a cancelled request", async () => {
    api.mockImplementation((path: string) => {
      const url = String(path);
      if (isPublicResolve(url)) return Promise.resolve(publicResolveResponse());
      if (url.includes("/admin/repositories?")) return Promise.resolve([repository]);
      if (url.includes("/competitors/")) return Promise.reject(new Error("Repository was not found in this topic"));
      if (url.endsWith("/admin/repositories/13")) return Promise.resolve(repository);
      if (url.includes("/history")) return Promise.resolve(topicHistory);
      return Promise.resolve([]);
    });
    renderTopics(`${base}/topics/outbox/competitors/404`);
    expect(await screen.findByText("Repository was not found in this topic")).toBeInTheDocument();
    cleanup();

    api.mockImplementation((path: string) => {
      const url = String(path);
      if (isPublicResolve(url)) return Promise.resolve(publicResolveResponse());
      if (url.includes("/admin/repositories?")) return Promise.resolve([repository]);
      if (url.endsWith("/admin/repositories/13")) return Promise.resolve(repository);
      if (url.includes("/history")) return Promise.resolve(topicHistory);
      return Promise.reject("nope");
    });
    renderTopics(`${base}/topics/outbox/competitors/2`);
    expect(await screen.findByText("Failed to load the competitor card")).toBeInTheDocument();
    cleanup();

    api.mockImplementation(() => new Promise(() => undefined));
    const pending = renderTopics(`${base}/topics/outbox/competitors/2`);
    pending.unmount();

    api.mockReset();
    api.mockImplementation((path: string) => {
      const url = String(path);
      if (isPublicResolve(url)) return Promise.resolve(publicResolveResponse());
      if (url.includes("/admin/repositories?")) return Promise.resolve([repository]);
      if (url.endsWith("/admin/repositories/13")) return Promise.resolve(repository);
      if (url.includes("/history")) return Promise.resolve(topicHistory);
      return Promise.resolve(card);
    });
    renderTopics(`${base}/topics/outbox/competitors/0`);
    expect(screen.getByText("Loading competitor card...")).toBeInTheDocument();
    await waitFor(() => expect(api.mock.calls.some((call) => String(call[0]).includes("/competitors/0"))).toBe(false));
    cleanup();

    renderTopics("/no-topic/KHolodilin/spring-transactional-outbox-kafka/2");
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
      if (isPublicResolve(url)) return Promise.resolve(publicResolveResponse());
      if (url.includes("/admin/repositories?")) return Promise.resolve([repository]);
      if (url.includes("/competitors/2")) return new Promise((resolve) => { settle = resolve; });
      if (url.includes("/competitors/9")) return new Promise((_, reject) => { fail = reject; });
      if (url.endsWith("/admin/repositories/13")) return Promise.resolve(repository);
      if (url.includes("/history")) return Promise.resolve(topicHistory);
      return Promise.resolve([]);
    });

    const first = renderTopics(`${base}/topics/outbox/competitors/2`);
    await waitFor(() => expect(settle).not.toBeNull());
    first.unmount();
    settle?.(card);

    const second = renderTopics(`${base}/topics/outbox/competitors/9`);
    await waitFor(() => expect(fail).not.toBeNull());
    second.unmount();
    fail?.(new Error("late"));
    await Promise.resolve();
  });
});
