import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes, useSearchParams } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { repository, topicHistory, traffic } from "../test/fixtures";
import { readTopicScope, topicScopeCookie, writeTopicScope } from "../lib/topicScope";
import { RepositoryDetailsPage } from "./RepositoryDetailsPage";
import { TopicDetailsPage } from "./TopicDetailsPage";

const { api } = vi.hoisted(() => ({ api: vi.fn() }));
vi.mock("../lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../lib/api")>();
  return { ...actual, api };
});

function SearchProbe() {
  const [params] = useSearchParams();
  return <div data-testid="search">{params.toString()}</div>;
}

function renderRepo(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route
          path="/repositories/:id"
          element={(
            <>
              <RepositoryDetailsPage />
              <SearchProbe />
            </>
          )}
        />
        <Route
          path="/repositories/:repositoryId/topics/:topic"
          element={(
            <>
              <TopicDetailsPage />
              <SearchProbe />
            </>
          )}
        />
      </Routes>
    </MemoryRouter>,
  );
}

describe("topics visibility scope cookie", () => {
  beforeEach(() => {
    api.mockReset();
    document.cookie = `${topicScopeCookie(13)}=; path=/; max-age=0`;
    document.cookie = `${topicScopeCookie(8)}=; path=/; max-age=0`;
    api.mockImplementation((path: string) => {
      const url = String(path);
      if (url.endsWith("/repositories/13") || url.endsWith("/repositories/8")) {
        const id = url.endsWith("/repositories/8") ? 8 : 13;
        return Promise.resolve({
          ...repository,
          id,
          name: id === 8 ? "other" : repository.name,
          fullName: id === 8 ? "KHolodilin/other" : repository.fullName,
          language: "Java",
        });
      }
      if (url.includes("/repositories?")) {
        return Promise.resolve([
          repository,
          { ...repository, id: 8, name: "other", fullName: "KHolodilin/other" },
        ]);
      }
      if (url.includes("/traffic?")) {
        return Promise.resolve(traffic);
      }
      if (url.includes("/topics/") && url.includes("history")) {
        return Promise.resolve(topicHistory);
      }
      if (url.includes("/topics/") && url.includes("results")) {
        return Promise.resolve({ rows: [] });
      }
      if (url.includes("topics-visibility")) {
        const topic = url.includes("scope=language") ? "kafka" : "outbox";
        return Promise.resolve([{ ...topicHistory, watch: { ...topicHistory.watch, topic } }]);
      }
      return Promise.resolve([]);
    });
  });

  it("stays on All when the cookie is missing", async () => {
    renderRepo("/repositories/13?tab=topics");
    expect(await screen.findByRole("button", { name: "All" })).toHaveClass("bg-card");
    expect(screen.getByText("outbox")).toBeInTheDocument();
    expect(readTopicScope(13)).toBeNull();
    expect(screen.getByTestId("search")).toHaveTextContent("tab=topics");
    expect(screen.getByTestId("search")).not.toHaveTextContent("scope=");
  });

  it("restores Java from the cookie and keeps it in the query", async () => {
    writeTopicScope(13, "language");
    renderRepo("/repositories/13?tab=topics");
    expect(await screen.findByRole("button", { name: "Java" })).toHaveClass("bg-card");
    expect(screen.getByText("kafka")).toBeInTheDocument();
    await waitFor(() => expect(screen.getByTestId("search").textContent).toContain("scope=language"));
    expect(readTopicScope(13)).toBe("language");
  });

  it("lets a shared language link win and saves it", async () => {
    renderRepo("/repositories/13?tab=topics&scope=language");
    expect(await screen.findByRole("button", { name: "Java" })).toHaveClass("bg-card");
    await waitFor(() => expect(readTopicScope(13)).toBe("language"));
    expect(screen.getByText("kafka")).toBeInTheDocument();
  });

  it("saves All and opens All on the next visit without a query", async () => {
    const user = userEvent.setup();
    writeTopicScope(13, "language");
    const view = renderRepo("/repositories/13?tab=topics");
    await user.click(await screen.findByRole("button", { name: "All" }));
    await waitFor(() => expect(readTopicScope(13)).toBe("all"));
    await waitFor(() => expect(screen.getByTestId("search").textContent ?? "").not.toContain("scope="));
    expect(screen.getByText("outbox")).toBeInTheDocument();
    view.unmount();

    renderRepo("/repositories/13?tab=topics");
    expect(await screen.findByRole("button", { name: "All" })).toHaveClass("bg-card");
    expect(screen.queryByText("kafka")).not.toBeInTheDocument();
  });

  it("keeps the scope when the period or a topic checkbox changes", async () => {
    const user = userEvent.setup();
    renderRepo("/repositories/13?tab=topics&scope=language");
    expect(await screen.findByText("kafka")).toBeInTheDocument();
    await user.click(screen.getAllByRole("button", { name: "7d" })[0]);
    await user.click(screen.getByRole("checkbox", { name: "Chart line for kafka" }));
    expect(readTopicScope(13)).toBe("language");
    expect(screen.getByRole("button", { name: "Java" })).toHaveClass("bg-card");
  });

  it("keeps another repository on its own scope", async () => {
    const user = userEvent.setup();
    renderRepo("/repositories/13?tab=topics");
    await user.click(await screen.findByRole("button", { name: "Java" }));
    await waitFor(() => expect(readTopicScope(13)).toBe("language"));
    await user.click(screen.getByLabelText("Switch repository"));
    await user.click(await screen.findByRole("option", { name: "other" }));
    expect(await screen.findByText("KHolodilin/other")).toBeInTheDocument();
    await waitFor(() => expect(screen.getByTestId("search").textContent).toBe("tab=topics"));
    expect(screen.getByRole("button", { name: "All" })).toHaveClass("bg-card");
    expect(readTopicScope(8)).toBeNull();
    expect(readTopicScope(13)).toBe("language");
  });

  it("clears a saved language choice when the repository has no language", async () => {
    writeTopicScope(13, "language");
    api.mockImplementation((path: string) => {
      const url = String(path);
      if (url.endsWith("/repositories/13")) {
        return Promise.resolve({ ...repository, language: null });
      }
      if (url.includes("/traffic?")) {
        return Promise.resolve(traffic);
      }
      if (url.includes("topics-visibility")) {
        return Promise.resolve([topicHistory]);
      }
      return Promise.resolve([]);
    });
    renderRepo("/repositories/13?tab=topics");
    expect(await screen.findByText("outbox")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Java" })).not.toBeInTheDocument();
    await waitFor(() => expect(readTopicScope(13)).toBe("all"));
    expect(screen.getByTestId("search").textContent).toBe("tab=topics");
  });

  it("falls back to All when the repository has no language", async () => {
    writeTopicScope(13, "language");
    api.mockImplementation((path: string) => {
      const url = String(path);
      if (url.endsWith("/repositories/13")) {
        return Promise.resolve({ ...repository, language: null });
      }
      if (url.includes("/traffic?")) {
        return Promise.resolve(traffic);
      }
      if (url.includes("topics-visibility")) {
        return Promise.resolve([topicHistory]);
      }
      return Promise.resolve([]);
    });
    renderRepo("/repositories/13?tab=topics&scope=language");
    expect(await screen.findByText("outbox")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Java" })).not.toBeInTheDocument();
    await waitFor(() => expect(readTopicScope(13)).toBe("all"));
    await waitFor(() => expect(screen.getByTestId("search").textContent ?? "").not.toContain("scope="));
  });

  it("treats an invalid scope query as All and saves that", async () => {
    writeTopicScope(13, "language");
    renderRepo("/repositories/13?tab=topics&scope=nope");
    expect(await screen.findByRole("button", { name: "All" })).toHaveClass("bg-card");
    await waitFor(() => expect(readTopicScope(13)).toBe("all"));
    await waitFor(() => expect(screen.getByTestId("search").textContent ?? "").not.toContain("scope="));
  });

  it("restores the cookie when the topics tab is opened from another tab", async () => {
    const user = userEvent.setup();
    writeTopicScope(13, "language");
    renderRepo("/repositories/13");
    await user.click(await screen.findByRole("button", { name: "Topics Visibility" }));
    expect(await screen.findByRole("button", { name: "Java" })).toHaveClass("bg-card");
    expect(screen.getByText("kafka")).toBeInTheDocument();
  });

  it("saves a topic page scope query without restoring a missing one", async () => {
    const user = userEvent.setup();
    const linked = renderRepo("/repositories/13/topics/outbox?scope=language");
    expect(await screen.findByRole("heading", { name: "outbox" })).toBeInTheDocument();
    await waitFor(() => expect(readTopicScope(13)).toBe("language"));
    expect(screen.getByRole("button", { name: "Java" })).toHaveClass("bg-card");
    await user.click(screen.getByLabelText("Switch repository"));
    await user.click(await screen.findByRole("option", { name: "other" }));
    expect(await screen.findByText("KHolodilin/other")).toBeInTheDocument();
    await waitFor(() => expect(screen.getByTestId("search").textContent).toBe("tab=topics"));
    expect(readTopicScope(8)).toBeNull();
    expect(readTopicScope(13)).toBe("language");
    linked.unmount();

    writeTopicScope(13, "language");
    const plain = renderRepo("/repositories/13/topics/outbox");
    expect(await screen.findByRole("button", { name: "All" })).toHaveClass("bg-card");
    expect(readTopicScope(13)).toBe("language");
    await user.click(screen.getByRole("button", { name: "All" }));
    await waitFor(() => expect(readTopicScope(13)).toBe("all"));
    plain.unmount();

    writeTopicScope(13, "all");
    renderRepo("/repositories/13/topics/outbox");
    await user.click(await screen.findByRole("button", { name: "Java" }));
    await waitFor(() => expect(readTopicScope(13)).toBe("language"));
  });

  it("drops language scope on a topic page when the repository has no language", async () => {
    api.mockImplementation((path: string) => {
      const url = String(path);
      if (url.endsWith("/repositories/13")) {
        return Promise.resolve({ ...repository, language: null });
      }
      if (url.includes("history")) {
        return Promise.resolve(topicHistory);
      }
      return Promise.resolve({ rows: [] });
    });
    const invalid = renderRepo("/repositories/13/topics/outbox?scope=nope");
    expect(await screen.findByRole("heading", { name: "outbox" })).toBeInTheDocument();
    await waitFor(() => expect(readTopicScope(13)).toBe("all"));
    expect(screen.getByTestId("search")).toHaveTextContent("scope=nope");
    invalid.unmount();

    renderRepo("/repositories/13/topics/outbox?scope=language");
    expect(await screen.findByRole("heading", { name: "outbox" })).toBeInTheDocument();
    await waitFor(() => expect(screen.getByTestId("search").textContent ?? "").not.toContain("scope="));
    expect(readTopicScope(13)).toBe("all");
  });
});
