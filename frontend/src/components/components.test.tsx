import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it, vi } from "vitest";
import { growthEvent, repository, searchResults, snapshotHistory, topicResults, traffic } from "../test/fixtures";
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
});

async function waitForChange(onChanged: ReturnType<typeof vi.fn>) {
  const { waitFor } = await import("@testing-library/react");
  await waitFor(() => expect(onChanged).toHaveBeenCalled());
}
