import { act, cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";
import { dashboardReady, growthEvent, searchResults, snapshotHistory, topicResults, traffic } from "../test/fixtures";
import { GrowthEventSettingsCard } from "./GrowthEventSettingsCard";
import { EventDetailsDialog, GrowthEventsPanel } from "./GrowthEventsPanel";
import { DashboardIcon, LogoMark, RepositoriesIcon, SettingsIcon } from "./icons";
import { Layout } from "./Layout";
import { PageBreadcrumb } from "./PageBreadcrumb";
import { PeriodSelector } from "./PeriodSelector";
import { PersistentECharts } from "./PersistentECharts";
import { ReferrerSourceIcon, referrerLineColor } from "./ReferrerSourceIcon";
import { SearchResultsTable } from "./SearchResultsTable";
import { ServicePathBadge, ServicePathToggle } from "./ServicePaths";
import { SnapshotCards } from "./SnapshotCards";
import { SnapshotHistoryChart } from "./SnapshotHistoryChart";
import { SnapshotHistoryTable } from "./SnapshotHistoryTable";
import { TopicResultsTable } from "./TopicResultsTable";
import { Button, Card, Skeleton } from "./ui";

const { api } = vi.hoisted(() => ({ api: vi.fn() }));
vi.mock("../lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../lib/api")>();
  return { ...actual, api };
});

describe("components", () => {
  it("renders primitives icons layout and period selector", async () => {
    const user = userEvent.setup();
    const onPeriod = vi.fn();
    render(
      <MemoryRouter>
        <Layout />
        <Button>Go</Button>
        <Card>Box</Card>
        <Skeleton className="h-4" />
        <LogoMark />
        <DashboardIcon />
        <RepositoriesIcon />
        <SettingsIcon />
        <PeriodSelector period="30d" onPeriod={onPeriod} />
        <ServicePathBadge />
        <ServicePathToggle checked={false} onChange={vi.fn()} />
        <PageBreadcrumb items={[{ label: "Home", to: "/" }, { label: "Now" }]} />
      </MemoryRouter>,
    );
    expect(screen.getByText("Repo Growth")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "7d" }));
    expect(onPeriod).toHaveBeenCalledWith("7d");
  });

  it("collects from the header and reports sync failures", async () => {
    const user = userEvent.setup();
    api.mockImplementation((path: string) => {
      if (String(path).includes("/collect")) {
        return {};
      }
      return dashboardReady;
    });
    const ready = render(
      <MemoryRouter initialEntries={["/dashboard"]}>
        <Routes>
          <Route element={<Layout />}>
            <Route path="/dashboard" element={<p>dash</p>} />
            <Route path="/repositories" element={<p>repos</p>} />
          </Route>
        </Routes>
      </MemoryRouter>,
    );
    expect(await screen.findByText(/Last sync: Sep/)).toBeInTheDocument();
    expect(screen.getByText(/About data/)).toBeInTheDocument();
    expect(screen.queryByText(/Last updated/)).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Collect 1 repository" }));
    await waitFor(() =>
      expect(api).toHaveBeenCalledWith("/api/v1/repositories/13/collect", { method: "POST" }),
    );
    await user.click(screen.getByRole("link", { name: "Repositories" }));
    expect(screen.getByText("repos")).toBeInTheDocument();
    ready.unmount();

    let hold = true;
    const waiters: Array<(value: unknown) => void> = [];
    api.mockImplementation((path: string) => {
      if (String(path).includes("/collect")) {
        return new Promise((resolve) => {
          if (!hold) {
            resolve({});
            return;
          }
          waiters.push(resolve);
        });
      }
      return dashboardReady;
    });
    const pending = render(
      <MemoryRouter>
        <Routes>
          <Route element={<Layout />}>
            <Route path="/" element={<p>home</p>} />
          </Route>
        </Routes>
      </MemoryRouter>,
    );
    await user.click(await screen.findByRole("button", { name: "Collect 1 repository" }));
    expect(screen.getByRole("button", { name: "Collect 1 repository" })).toBeDisabled();
    expect(screen.getByText("Collected 0 of 1")).toBeInTheDocument();
    hold = false;
    waiters.forEach((resolve) => resolve({}));
    await waitFor(() => expect(screen.getByRole("button", { name: "Collect 1 repository" })).toBeEnabled());
    pending.unmount();

    api.mockImplementation((path: string) => {
      if (String(path).includes("/collect")) {
        throw new Error("busy");
      }
      return dashboardReady;
    });
    const failed = render(
      <MemoryRouter>
        <Routes>
          <Route element={<Layout />}>
            <Route path="/" element={<p>home</p>} />
          </Route>
        </Routes>
      </MemoryRouter>,
    );
    await user.click(await screen.findByRole("button", { name: "Collect 1 repository" }));
    expect(await screen.findByText("busy")).toBeInTheDocument();
    failed.unmount();

    api.mockImplementation((path: string) => {
      if (String(path).includes("/collect")) {
        throw "nope";
      }
      return { lastSyncAt: null };
    });
    const empty = render(
      <MemoryRouter>
        <Routes>
          <Route element={<Layout />}>
            <Route path="/" element={<p>home</p>} />
          </Route>
        </Routes>
      </MemoryRouter>,
    );
    expect(await screen.findByRole("button", { name: "Select repositories first" })).toBeDisabled();
    expect(screen.getByText("Last sync: —")).toBeInTheDocument();
    empty.unmount();

    api.mockImplementation((path: string) => {
      if (String(path).includes("/collect")) {
        throw "nope";
      }
      return dashboardReady;
    });
    const plain = render(
      <MemoryRouter>
        <Routes>
          <Route element={<Layout />}>
            <Route path="/" element={<p>home</p>} />
          </Route>
        </Routes>
      </MemoryRouter>,
    );
    await user.click(await screen.findByRole("button", { name: "Collect 1 repository" }));
    expect(await screen.findByText("Collect failed")).toBeInTheDocument();
    plain.unmount();

    const rejectors: Array<(reason: Error) => void> = [];
    api.mockImplementation(
      () =>
        new Promise((_, reject) => {
          rejectors.push(reject);
        }),
    );
    const hanging = render(
      <MemoryRouter>
        <Layout />
      </MemoryRouter>,
    );
    hanging.unmount();
    await act(async () => {
      rejectors.forEach((reject) => reject(new Error("late")));
    });

    api.mockRejectedValue(new Error("down"));
    render(
      <MemoryRouter>
        <Layout />
      </MemoryRouter>,
    );
    expect(await screen.findByText("Last sync: —")).toBeInTheDocument();
  });

  it("collects one repository from its page and every repository from the list", async () => {
    const user = userEvent.setup();
    const portfolio = {
      ...dashboardReady,
      repositories: [
        dashboardReady.repositories[0],
        { ...dashboardReady.repositories[0], id: 8, fullName: "KHolodilin/other" },
      ],
    };
    api.mockImplementation((path: string) => {
      if (String(path).includes("/collect")) {
        return {};
      }
      return portfolio;
    });
    const repo = render(
      <MemoryRouter initialEntries={["/repositories/13/traffic/history"]}>
        <Routes>
          <Route element={<Layout />}>
            <Route path="/repositories/:id/traffic/history" element={<p>history</p>} />
          </Route>
        </Routes>
      </MemoryRouter>,
    );
    await user.click(await screen.findByRole("button", { name: "Collect this repository" }));
    await waitFor(() =>
      expect(api).toHaveBeenCalledWith("/api/v1/repositories/13/collect", { method: "POST" }),
    );
    expect(api).not.toHaveBeenCalledWith("/api/v1/repositories/8/collect", { method: "POST" });
    repo.unmount();

    api.mockClear();
    api.mockImplementation((path: string) => {
      if (String(path).includes("/collect")) {
        return {};
      }
      return portfolio;
    });
    render(
      <MemoryRouter initialEntries={["/repositories"]}>
        <Routes>
          <Route element={<Layout />}>
            <Route path="/repositories" element={<p>list</p>} />
          </Route>
        </Routes>
      </MemoryRouter>,
    );
    await user.click(await screen.findByRole("button", { name: "Collect 2 repositories" }));
    await waitFor(() =>
      expect(api).toHaveBeenCalledWith("/api/v1/repositories/8/collect", { method: "POST" }),
    );
    expect(api).toHaveBeenCalledWith("/api/v1/repositories/13/collect", { method: "POST" });
  });

  it("shows job progress under the header until the run finishes", async () => {
    const user = userEvent.setup();
    const timers: Array<() => void> = [];
    const spy = vi.spyOn(window, "setInterval").mockImplementation((handler) => {
      timers.push(() => {
        void (handler as () => void)();
      });
      return 1 as unknown as ReturnType<typeof setInterval>;
    });
    const clear = vi.spyOn(window, "clearInterval").mockImplementation(() => undefined);
    let phase: "running" | "done" = "running";
    let failPoll = false;
    let bare = false;
    api.mockImplementation((path: string) => {
      if (failPoll) {
        return Promise.reject(new Error("poll failed"));
      }
      if (bare || String(path).includes("/collect")) {
        return bare ? { lastSyncAt: null } : {};
      }
      const running = phase === "running";
      return {
        ...dashboardReady,
        repositories: [
          {
            ...dashboardReady.repositories[0],
            collectionStatus: running ? "RUNNING" : "SUCCESS",
            jobs: running
              ? [
                  { jobType: "TRAFFIC", status: "SUCCESS" },
                  { jobType: "REFERRERS", status: "FAILED", errorMessage: "rate limit" },
                ]
              : [{ jobType: "TRAFFIC", status: "SUCCESS" }],
          },
          { ...dashboardReady.repositories[0], id: 8, fullName: "KHolodilin/other", collectionStatus: "SUCCESS", jobs: [] },
        ],
      };
    });
    const view = render(
      <MemoryRouter initialEntries={["/repositories/13"]}>
        <Routes>
          <Route element={<Layout />}>
            <Route path="/repositories/:id" element={<p>repo</p>} />
          </Route>
        </Routes>
      </MemoryRouter>,
    );
    expect(
      await screen.findByText(
        "Collecting KHolodilin/spring-transactional-outbox-kafka: Traffic done, Referrers failed: rate limit",
      ),
    ).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Collect this repository" }));
    await waitFor(() => expect(api).toHaveBeenCalledWith("/api/v1/repositories/13/collect", { method: "POST" }));
    expect(timers.length).toBeGreaterThan(0);
    const fireTimers = async () => {
      await act(async () => {
        timers.forEach((run) => run());
        await new Promise((resolve) => setTimeout(resolve, 0));
      });
    };
    failPoll = true;
    await fireTimers();
    failPoll = false;
    await fireTimers();
    expect(screen.getByText(/Referrers failed: rate limit/)).toBeInTheDocument();
    bare = true;
    await fireTimers();
    bare = false;
    phase = "done";
    await fireTimers();
    await waitFor(() => expect(screen.queryByText(/Collecting KHolodilin/)).not.toBeInTheDocument());
    view.unmount();
    expect(clear).toHaveBeenCalled();
    spy.mockRestore();
    clear.mockRestore();
  });

  it("does not collect when no repository is tracked", async () => {
    const user = userEvent.setup();
    let release: (value: unknown) => void = () => undefined;
    let calls = 0;
    api.mockImplementation((path: string) => {
      if (String(path).includes("/collect")) {
        return {};
      }
      calls += 1;
      if (calls === 1) {
        return new Promise((resolve) => {
          release = resolve;
        });
      }
      return { lastSyncAt: null };
    });
    render(
      <MemoryRouter>
        <Routes>
          <Route element={<Layout />}>
            <Route path="/" element={<p>home</p>} />
          </Route>
        </Routes>
      </MemoryRouter>,
    );
    await user.click(await screen.findByRole("button", { name: "Collect repositories" }));
    await waitFor(() => expect(calls).toBeGreaterThan(1));
    expect(api).not.toHaveBeenCalledWith(expect.stringContaining("/collect"), expect.anything());
    release({ lastSyncAt: "2026-09-23T06:00:00Z", repositories: [] });
    expect(await screen.findByRole("button", { name: "Select repositories first" })).toBeDisabled();
  });

  it("maps referrer icons and colors", () => {
    const { rerender } = render(<ReferrerSourceIcon source="Other" />);
    for (const source of [
      "github.com",
      "chatgpt.com",
      "google.com",
      "reddit.com",
      "linkedin.com",
      "bing.com",
      "stackoverflow.com",
      "dev.to",
      "doubao",
      "example.com",
    ]) {
      rerender(<ReferrerSourceIcon source={source} className="h-4" />);
    }
    expect(referrerLineColor("github.com", 0)).toBe("#2563eb");
    expect(referrerLineColor("unknown", 1)).toBe("#f59e0b");
  });

  it("sorts result tables and snapshot cards", async () => {
    const user = userEvent.setup();
    render(
      <MemoryRouter>
        <SearchResultsTable rows={searchResults.rows} repositoryId={13} trackedGithubId={99} />
        <TopicResultsTable rows={topicResults.rows} repositoryId={13} trackedGithubId={99} />
        <SnapshotCards
          repositoryId={13}
          referrers={traffic.referrers}
          referrerSnapshotAt={traffic.referrerSnapshotAt}
          paths={traffic.paths}
          pathSnapshotAt={traffic.pathSnapshotAt}
          showServicePaths
          onShowServicePathsChange={vi.fn()}
        />
      </MemoryRouter>,
    );
    await user.click(screen.getAllByRole("button", { name: /^Stars/ })[0]);
    await user.click(screen.getAllByRole("button", { name: /^Repository/ })[0]);
    await user.click(screen.getAllByRole("button", { name: /^Visitors/ })[0]);
    expect(screen.getByText("Top Referrers")).toBeInTheDocument();
  });

  it("manages growth events and settings", async () => {
    const user = userEvent.setup();
    api.mockResolvedValue([{ repositoryId: 13, eventType: "STAR_MILESTONE", enabled: true }]);
    const onChanged = vi.fn();
    render(
      <GrowthEventsPanel
        repositoryId={13}
        events={[growthEvent, { ...growthEvent, id: 2, source: "GITHUB", title: "Stars" }]}
        onChanged={onChanged}
      />,
    );
    await user.click(screen.getByRole("button", { name: "View all →" }));
    await user.click(screen.getByRole("button", { name: "Close" }));
    await user.click(screen.getByText("LinkedIn launch"));
    expect(screen.getByRole("heading", { name: "2 events" })).toBeInTheDocument();
    expect(screen.getAllByText("Stars").length).toBeGreaterThan(1);
    await user.click(screen.getAllByRole("button", { name: "Edit" })[0]);
    await user.click(screen.getByRole("button", { name: "Cancel" }));
    api.mockResolvedValueOnce({});
    await user.click(screen.getAllByRole("button", { name: "Delete" })[0]);
    await waitForChange(onChanged);

    render(<GrowthEventSettingsCard repositoryId={13} />);
    expect(await screen.findByText("Star milestones")).toBeInTheDocument();
    api.mockResolvedValueOnce([{ repositoryId: 13, eventType: "STAR_MILESTONE", enabled: false }]);
    await user.click(screen.getAllByRole("checkbox")[0]);

    const onClose = vi.fn();
    render(
      <EventDetailsDialog
        repositoryId={13}
        events={[{ ...growthEvent, description: "note", url: "https://example.com" }]}
        onClose={onClose}
        onEdit={vi.fn()}
        onChanged={vi.fn()}
      />,
    );
    expect(screen.getByText("Open link")).toBeInTheDocument();
  });

  it("opens the repo switcher and snapshot history widgets", async () => {
    const user = userEvent.setup();
    render(
      <MemoryRouter>
        <SnapshotHistoryTable kind="referrers" history={snapshotHistory} />
        <SnapshotHistoryTable kind="paths" history={{ ...snapshotHistory, dates: [], rows: [] }} />
        <SnapshotHistoryChart title="Referrer Traffic (by day)" kind="referrers" history={snapshotHistory} />
        <PersistentECharts chartId="x" series={[{ key: "Views", name: "Views" }]} option={{ legend: {} }} />
      </MemoryRouter>,
    );
    await user.click(screen.getAllByRole("button", { name: /^Views/ })[0]);
    expect(screen.getByText("No snapshots for this period.")).toBeInTheDocument();
  });

  it("shows one before and after table for the day's events", async () => {
    api.mockImplementation((url: string) => {
      const path = String(url);
      if (path.includes("traffic")) {
        return Promise.resolve({
          traffic: [
            { date: "2026-09-30", uniqueVisitors: 100, clones: 18 },
            { date: "2026-10-04", uniqueVisitors: 20, clones: null },
            { date: "2026-10-05", uniqueVisitors: 340, clones: 40 },
          ],
        });
      }
      if (path.includes("stats-history")) {
        return Promise.resolve({
          points: [
            { date: "2026-09-27", stars: 10 },
            { date: "2026-10-04", stars: 11 },
            { date: "2026-10-11", stars: 17 },
          ],
        });
      }
      if (path.includes("search-visibility")) {
        return Promise.resolve([
          {
            query: { name: "outbox" },
            points: [
              { date: "2026-10-04", position: 22 },
              { date: "2026-10-08", position: 14 },
            ],
          },
        ]);
      }
      return Promise.resolve([]);
    });
    render(
      <EventDetailsDialog
        repositoryId={13}
        today="2026-10-12"
        events={[
          { ...growthEvent, eventAt: "2026-10-05T12:00:00Z", title: "README change" },
          { ...growthEvent, id: 2, eventAt: "2026-10-05T15:00:00Z", title: "Merged pull request", source: "GITHUB" },
        ]}
        onClose={vi.fn()}
        onEdit={vi.fn()}
        onChanged={vi.fn()}
      />,
    );
    expect(screen.getByRole("heading", { name: "2 events" })).toBeInTheDocument();
    expect(screen.getByText("README change")).toBeInTheDocument();
    expect(screen.getByText("Merged pull request")).toBeInTheDocument();
    expect(await screen.findByText("A match in time does not prove a cause.")).toBeInTheDocument();
    expect(screen.getByText("120")).toBeInTheDocument();
    expect(screen.getByText("340")).toBeInTheDocument();
    expect(screen.getByText("+220")).toBeInTheDocument();
    expect(screen.getByText("+1")).toBeInTheDocument();
    expect(screen.getByText("+6")).toBeInTheDocument();
    expect(screen.getByText("+5")).toBeInTheDocument();
    expect(screen.getByText("outbox 22 to 14")).toBeInTheDocument();
    expect(screen.getByText("+8")).toBeInTheDocument();
    expect(screen.queryByText(/of 7 days/)).not.toBeInTheDocument();
  });

  it("labels a short after window and missing queries", async () => {
    api.mockResolvedValue([]);
    render(
      <EventDetailsDialog
        repositoryId={13}
        today="2026-10-08"
        events={[{ ...growthEvent, eventAt: "2026-10-05T12:00:00Z", title: "README change" }]}
        onClose={vi.fn()}
        onEdit={vi.fn()}
        onChanged={vi.fn()}
      />,
    );
    expect(await screen.findByText("3 of 7 days")).toBeInTheDocument();
    expect(screen.getByText("No queries")).toBeInTheDocument();
    cleanup();

    api.mockResolvedValue({});
    render(
      <EventDetailsDialog
        repositoryId={13}
        today="2026-10-12"
        events={[]}
        onClose={vi.fn()}
        onEdit={vi.fn()}
        onChanged={vi.fn()}
      />,
    );
    expect(screen.getByRole("heading", { name: "0 events" })).toBeInTheDocument();
    cleanup();

    api.mockResolvedValue({});
    render(
      <EventDetailsDialog
        repositoryId={13}
        today="2026-10-12"
        events={[growthEvent]}
        onClose={vi.fn()}
        onEdit={vi.fn()}
        onChanged={vi.fn()}
      />,
    );
    expect(await screen.findByText("No queries")).toBeInTheDocument();
    cleanup();

    api.mockResolvedValue([{ enabled: true }]);
    render(
      <EventDetailsDialog
        repositoryId={13}
        today="2026-10-12"
        events={[{ ...growthEvent, id: 9, title: "Skipped query shape" }]}
        onClose={vi.fn()}
        onEdit={vi.fn()}
        onChanged={vi.fn()}
      />,
    );
    expect(await screen.findByText("No queries")).toBeInTheDocument();
    cleanup();

    api.mockImplementation((url: string) => {
      if (String(url).includes("search-visibility")) {
        return Promise.resolve([{ query: { name: "outbox" } }, { query: { name: "outbox" }, points: [{ date: "2026-01-01", position: 3 }] }]);
      }
      return Promise.resolve({ traffic: [], points: [] });
    });
    render(
      <EventDetailsDialog
        repositoryId={13}
        today="2026-10-12"
        events={[{ ...growthEvent, title: "No rank move" }]}
        onClose={vi.fn()}
        onEdit={vi.fn()}
        onChanged={vi.fn()}
      />,
    );
    expect(await screen.findByText("A match in time does not prove a cause.")).toBeInTheDocument();
    expect(screen.queryByText("outbox 3 to 3")).not.toBeInTheDocument();
    expect(screen.queryByText("No queries")).not.toBeInTheDocument();
  });

  it("still shows the comparison when the series cannot be loaded", async () => {
    api.mockRejectedValue(new Error("down"));
    render(
      <EventDetailsDialog
        repositoryId={13}
        today="2026-10-12"
        events={[{ ...growthEvent, eventAt: "2026-10-05T12:00:00Z" }]}
        onClose={vi.fn()}
        onEdit={vi.fn()}
        onChanged={vi.fn()}
      />,
    );
    expect(await screen.findByText("No queries")).toBeInTheDocument();

    const pending: { resolve: (value: unknown) => void; reject: (reason?: unknown) => void }[] = [];
    api.mockImplementation(
      () =>
        new Promise((resolve, reject) => {
          pending.push({ resolve, reject });
        }),
    );
    const late = render(
      <EventDetailsDialog
        repositoryId={13}
        events={[growthEvent]}
        onClose={vi.fn()}
        onEdit={vi.fn()}
        onChanged={vi.fn()}
      />,
    );
    late.unmount();
    pending.forEach((item) => item.resolve({}));
    await act(async () => {
      await Promise.resolve();
    });
    const again: typeof pending = [];
    api.mockImplementation(
      () =>
        new Promise((resolve, reject) => {
          again.push({ resolve, reject });
        }),
    );
    const failed = render(
      <EventDetailsDialog
        repositoryId={13}
        events={[growthEvent]}
        onClose={vi.fn()}
        onEdit={vi.fn()}
        onChanged={vi.fn()}
      />,
    );
    failed.unmount();
    again.forEach((item) => item.reject(new Error("down")));
    await act(async () => {
      await Promise.resolve();
    });
  });
});

async function waitForChange(onChanged: ReturnType<typeof vi.fn>) {
  const { waitFor } = await import("@testing-library/react");
  await waitFor(() => expect(onChanged).toHaveBeenCalled());
}
