import { useCallback, useEffect, useMemo, useState } from "react";
import { Link, useParams, useSearchParams } from "react-router-dom";
import {
  api,
  type CollectionJob,
  type CollectionRun,
  type GrowthEvent,
  type Repository,
  type RepositoryHealth,
  type RepositoryTraffic,
  type SearchHistory,
} from "../lib/api";
import { cn, formatChartAxisDate, formatDelta, formatNumber, formatQueryRankChange, formatRank, formatSyncTime, growthClass } from "../lib/utils";
import { datesFromHistory, rankHistoryOption } from "../lib/rankChart";
import { Button, Card, Skeleton } from "../components/ui";
import { PageBreadcrumb } from "../components/PageBreadcrumb";
import { PeriodSelector, usePeriod, type Period } from "../components/PeriodSelector";
import { PersistentECharts } from "../components/PersistentECharts";
import { ReferrerTrafficPanel } from "../components/ReferrerTrafficPanel";
import { EventDetailsDialog, GrowthEventsPanel } from "../components/GrowthEventsPanel";
import { GrowthEventSettingsCard } from "../components/GrowthEventSettingsCard";
import { pruneChartSelection, repoSearchChartId, repoTrafficChartId, TRAFFIC_SERIES } from "../lib/chartLegend";
import { filterGrowthEvents, type EventFilter } from "../lib/growthEvents";
import { markLineEvents, trafficChartOption } from "../lib/trafficChart";

type Tab = "overview" | "traffic" | "search" | "growth-events";

const TAB_LABEL: Record<Tab, string> = {
  traffic: "Traffic",
  search: "Search Visibility",
  overview: "Overview",
  "growth-events": "Growth Events",
};

function parseTab(tabParam: string | null): Tab {
  if (tabParam === "overview" || tabParam === "search" || tabParam === "growth-events") {
    return tabParam;
  }
  if (tabParam === "settings") {
    return "growth-events";
  }
  return "traffic";
}

const JOB_LABELS: Record<string, string> = {
  TRAFFIC: "Traffic",
  REFERRERS: "Referrers",
  POPULAR_PATHS: "Popular Paths",
  REPOSITORY_STATS: "Repository Stats",
  GROWTH_EVENTS: "Growth Events",
};

export function RepositoryDetailsPage() {
  const { id } = useParams();
  const [searchParams, setSearchParams] = useSearchParams();
  const tabParam = searchParams.get("tab");
  const tab = parseTab(tabParam);
  const [period, setPeriod] = usePeriod(id);
  const [repo, setRepo] = useState<Repository | null>(null);
  const [traffic, setTraffic] = useState<RepositoryTraffic | null>(null);
  const [visibility, setVisibility] = useState<SearchHistory[]>([]);
  const [queryText, setQueryText] = useState("");
  const [queryError, setQueryError] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [collecting, setCollecting] = useState(false);
  const [runningQueryId, setRunningQueryId] = useState<number | null>(null);
  const [runningAll, setRunningAll] = useState(false);

  function setTab(next: Tab) {
    if (next === "traffic") {
      setSearchParams({}, { replace: true });
      return;
    }
    setSearchParams({ tab: next }, { replace: true });
  }

  const load = useCallback(async () => {
    if (!id) {
      return;
    }
    const [repository, trafficData, searchData] = await Promise.all([
      api<Repository>(`/api/v1/repositories/${id}`),
      api<RepositoryTraffic>(`/api/v1/repositories/${id}/traffic?period=${period}`),
      api<SearchHistory[]>(`/api/v1/repositories/${id}/search-visibility`),
    ]);
    setRepo(repository);
    setTraffic(trafficData);
    setVisibility(searchData);
  }, [id, period]);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    load()
      .catch((err: Error) => {
        if (!cancelled) {
          setError(err.message);
        }
      })
      .finally(() => {
        if (!cancelled) {
          setLoading(false);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [load]);

  const runStatus = traffic?.lastCollection?.status;
  const runActive = runStatus === "RUNNING" || runStatus === "PLANNED";
  const searchActive = visibility.some((item) => {
    const status = item.searchStatus;
    return status === "RUNNING" || status === "READY" || status === "RETRY";
  });

  useEffect(() => {
    if (!runActive) {
      setCollecting(false);
    }
    if (!searchActive) {
      setRunningQueryId(null);
      setRunningAll(false);
    }
    if (!runActive && !searchActive) {
      return;
    }
    const timer = window.setInterval(() => {
      void load().catch(() => undefined);
    }, 2500);
    return () => window.clearInterval(timer);
  }, [runActive, searchActive, load]);

  async function collect() {
    if (!id || collecting || runActive) {
      return;
    }
    setCollecting(true);
    try {
      await api(`/api/v1/repositories/${id}/collect`, { method: "POST" });
      await load();
    } catch (err) {
      setError((err as Error).message);
      setCollecting(false);
    }
  }

  async function createQuery() {
    if (!id) {
      return;
    }
    const query = normalizeSearchQuery(queryText);
    if (!query) {
      return;
    }
    if (visibility.some((item) => normalizeSearchQuery(item.query.query) === query)) {
      setQueryError("This search query is already tracked for the repository");
      return;
    }
    try {
      await api(`/api/v1/repositories/${id}/search-queries`, {
        method: "POST",
        body: JSON.stringify({ name: query, query, enabled: true, resultLimit: 50 }),
      });
      setQueryText("");
      setQueryError(null);
      await load();
    } catch (err) {
      setQueryError((err as Error).message);
    }
  }

  async function runQuery(queryId: number) {
    if (runningQueryId != null || runningAll || searchActive) {
      return;
    }
    setRunningQueryId(queryId);
    try {
      await api(`/api/v1/search-queries/${queryId}/run`, { method: "POST" });
      await load();
    } finally {
      setRunningQueryId(null);
    }
  }

  async function runAllQueries() {
    if (!id || runningQueryId != null || runningAll || searchActive || visibility.length === 0) {
      return;
    }
    setRunningAll(true);
    try {
      await api(`/api/v1/repositories/${id}/search-queries/run`, { method: "POST" });
      await load();
    } catch (err) {
      setQueryError((err as Error).message);
      setRunningAll(false);
    }
  }

  async function deleteQuery(queryId: number) {
    if (!id) {
      return;
    }
    try {
      await api(`/api/v1/search-queries/${queryId}`, { method: "DELETE" });
      pruneChartSelection(
        repoSearchChartId(id),
        visibility.filter((item) => item.query.id !== queryId).map((item) => String(item.query.id)),
      );
      await load();
    } catch (err) {
      setError((err as Error).message);
    }
  }

  if (error) {
    return <p className="text-red-600">{error}</p>;
  }
  if (loading || !repo || !traffic) {
    return (
      <div className="space-y-4">
        <div className="space-y-3">
          <Skeleton className="h-7 w-72" />
          <Skeleton className="h-4 w-full max-w-2xl" />
          <div className="flex items-center justify-between gap-4 border-t pt-3">
            <Skeleton className="h-4 w-64" />
            <Skeleton className="h-9 w-28" />
          </div>
        </div>
        <div className="grid gap-4 md:grid-cols-2">
          <Skeleton className="h-64" />
          <Skeleton className="h-64" />
        </div>
      </div>
    );
  }

  const busy = collecting || runActive;

  return (
    <div className="space-y-6">
      <header className="space-y-0">
        <PageBreadcrumb
          items={[
            { label: "Portfolio", to: "/dashboard" },
            {
              label: repo.fullName,
              repoSwitcher: {
                currentId: repo.id,
                hrefFor: (repositoryId) =>
                  tab === "traffic" ? `/repositories/${repositoryId}` : `/repositories/${repositoryId}?tab=${tab}`,
              },
            },
            { label: TAB_LABEL[tab] },
          ]}
        />
        {repo.description && (
          <p className="mt-3 border-t pt-3 text-sm font-normal text-muted-foreground">{repo.description}</p>
        )}
        <div className="mt-3 flex flex-wrap items-center justify-between gap-x-4 gap-y-2 border-t pt-3">
          <div className="flex min-w-0 flex-wrap items-center gap-x-4 gap-y-1 text-sm text-muted-foreground">
            <a
              className="text-primary hover:underline"
              href={`${repo.githubUrl ?? `https://github.com/${repo.fullName}`}/graphs/traffic`}
              target="_blank"
              rel="noreferrer"
            >
              Github Traffic
            </a>
            <span className="inline-flex items-center gap-1">
              <StarIcon />
              {formatNumber(repo.stars)} stars
            </span>
            <span className="inline-flex items-center gap-1">
              <ForkIcon />
              {formatNumber(repo.forks)} forks
            </span>
            <span className="inline-flex items-center gap-1">
              <WatchIcon />
              {formatNumber(repo.watchers)} watchers
            </span>
            <span className="inline-flex items-center gap-1">
              <ContributorsIcon />
              {formatNumber(repo.contributors)} contributors
            </span>
          </div>
          <Button className="shrink-0" disabled={busy} onClick={() => void collect()}>
            {busy ? "Collecting..." : "Collect now"}
          </Button>
        </div>
      </header>
      {repo.health && <HealthScoreRow health={repo.health} />}
      <div className="inline-flex rounded-lg border bg-muted p-1">
        {([
          ["traffic", "Traffic"],
          ["search", "Search Visibility"],
          ["overview", "Overview"],
          ["growth-events", "Growth Events"],
        ] as const).map(([key, label]) => (
          <button
            key={key}
            type="button"
            className={cn(
              "rounded-md px-3 py-1.5 text-sm font-medium",
              tab === key ? "bg-card text-foreground shadow-sm" : "text-muted-foreground hover:text-foreground",
            )}
            onClick={() => setTab(key)}
          >
            {label}
          </button>
        ))}
      </div>
      {tab === "overview" && (
        <Overview
          repo={repo}
          lastCollection={traffic.lastCollection}
          busy={busy}
          onCollect={() => void collect()}
        />
      )}
      {tab === "growth-events" && (
        <GrowthEventsTab repositoryId={repo.id} period={period} onPeriod={setPeriod} />
      )}
      {tab === "traffic" && (
        <TrafficPanel repositoryId={repo.id} traffic={traffic} period={period} onPeriod={setPeriod} />
      )}
      {tab === "search" && (
        <SearchPanel
          repositoryId={repo.id}
          visibility={visibility}
          queryText={queryText}
          setQueryText={(value) => {
            setQueryText(value);
            setQueryError(null);
          }}
          queryError={queryError}
          runningQueryId={runningQueryId}
          runningAll={runningAll}
          onCreate={() => void createQuery()}
          onRun={runQuery}
          onRunAll={() => void runAllQueries()}
          onDelete={(queryId) => void deleteQuery(queryId)}
        />
      )}
    </div>
  );
}

function StarIcon() {
  return (
    <svg viewBox="0 0 16 16" className="h-4 w-4 shrink-0" aria-hidden="true">
      <path
        fill="currentColor"
        d="M8 .25a.75.75 0 0 1 .673.418l1.882 3.815 4.21.612a.75.75 0 0 1 .416 1.279l-3.046 2.97.719 4.192a.75.75 0 0 1-1.088.791L8 12.347l-3.766 1.98a.75.75 0 0 1-1.088-.79l.72-4.194L.818 6.374a.75.75 0 0 1 .416-1.28l4.21-.611L7.327.668A.75.75 0 0 1 8 .25Zm0 2.445L6.615 5.5a.75.75 0 0 1-.564.41l-3.097.45 2.24 2.184a.75.75 0 0 1 .216.664l-.528 3.084 2.769-1.456a.75.75 0 0 1 .698 0l2.77 1.456-.53-3.084a.75.75 0 0 1 .216-.664l2.24-2.183-3.096-.45a.75.75 0 0 1-.564-.41L8 2.694Z"
      />
    </svg>
  );
}

function ForkIcon() {
  return (
    <svg viewBox="0 0 16 16" className="h-4 w-4 shrink-0" aria-hidden="true">
      <path
        fill="currentColor"
        d="M5 5.372v.878c0 .414.336.75.75.75h4.5a.75.75 0 0 0 .75-.75v-.878a2.25 2.25 0 1 1 1.5 0v.878a2.25 2.25 0 0 1-2.25 2.25h-1.5v2.128a2.251 2.251 0 1 1-1.5 0V8.5h-1.5A2.25 2.25 0 0 1 3.5 6.25v-.878a2.25 2.25 0 1 1 1.5 0ZM5 3.25a.75.75 0 1 0-1.5 0 .75.75 0 0 0 1.5 0Zm6.75.75a.75.75 0 1 0 0-1.5.75.75 0 0 0 0 1.5Zm-3 8.75a.75.75 0 1 0-1.5 0 .75.75 0 0 0 1.5 0Z"
      />
    </svg>
  );
}

function WatchIcon() {
  return (
    <svg viewBox="0 0 16 16" className="h-4 w-4 shrink-0" aria-hidden="true">
      <path
        fill="currentColor"
        d="M8 2c1.981 0 3.671.992 4.933 2.078 1.27 1.091 2.187 2.345 2.637 3.023a1.62 1.62 0 0 1 0 1.798c-.45.678-1.367 1.932-2.637 3.023C11.67 13.008 9.981 14 8 14c-1.981 0-3.671-.992-4.933-2.078C1.797 10.83.88 9.576.43 8.898a1.62 1.62 0 0 1 0-1.798c.45-.677 1.367-1.931 2.637-3.022C4.33 2.992 6.019 2 8 2ZM1.679 7.823C2.062 7.246 2.9 6.113 4.052 5.123 5.2 4.137 6.537 3.5 8 3.5s2.8.637 3.948 1.623c1.153.99 1.99 2.123 2.373 2.7a.137.137 0 0 1 0 .222c-.384.577-1.22 1.71-2.373 2.7-1.147.986-2.485 1.623-3.948 1.623s-2.8-.637-3.948-1.623c-1.153-.99-1.99-2.123-2.373-2.7a.137.137 0 0 1 0-.222ZM8 10.5a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5Zm0-1.5a1 1 0 1 1 0-2 1 1 0 0 1 0 2Z"
      />
    </svg>
  );
}

function ContributorsIcon() {
  return (
    <svg viewBox="0 0 16 16" className="h-4 w-4 shrink-0" aria-hidden="true">
      <path
        fill="currentColor"
        d="M2 5.5a3.5 3.5 0 1 1 5.898 2.549 5.508 5.508 0 0 1 3.034 4.084.75.75 0 1 1-1.482.235 4 4 0 0 0-7.9 0 .75.75 0 0 1-1.482-.236A5.507 5.507 0 0 1 3.102 8.05 3.493 3.493 0 0 1 2 5.5ZM11 4a3.001 3.001 0 0 1 2.22 5.018 5.01 5.01 0 0 1 2.7 3.412.75.75 0 0 1-1.477.248A3.492 3.492 0 0 0 12.5 9.5h-1.75a.75.75 0 0 1-.183-1.478A3.001 3.001 0 0 1 11 4Zm-5.5 1.5a2 2 0 1 0-.001 3.999A2 2 0 0 0 5.5 5.5Z"
      />
    </svg>
  );
}

function Overview({
  repo,
  lastCollection,
  busy,
  onCollect,
}: {
  repo: Repository;
  lastCollection?: CollectionRun;
  busy: boolean;
  onCollect: () => void;
}) {
  return (
    <div className="space-y-4">
      <div className="grid items-start gap-4 md:grid-cols-2">
        <Card>
          <h2 className="mb-4 font-medium">Overview</h2>
          <dl className="grid grid-cols-2 gap-x-4 gap-y-3 text-sm">
          <dt className="text-muted-foreground">Owner</dt>
          <dd>{repo.owner.login}</dd>
          <dt className="text-muted-foreground">Visibility</dt>
          <dd>{repo.visibility}</dd>
          <dt className="text-muted-foreground">Stars</dt>
          <dd>{formatNumber(repo.stars)}</dd>
          <dt className="text-muted-foreground">Watchers</dt>
          <dd>{formatNumber(repo.watchers)}</dd>
          <dt className="text-muted-foreground">Forks</dt>
          <dd>{formatNumber(repo.forks)}</dd>
          <dt className="text-muted-foreground">Contributors</dt>
          <dd>{formatNumber(repo.contributors)}</dd>
          <dt className="text-muted-foreground">Language</dt>
          <dd>{repo.language ?? "—"}</dd>
          <dt className="text-muted-foreground">Default branch</dt>
          <dd>{repo.defaultBranch ?? "—"}</dd>
          <dt className="text-muted-foreground">Last commit</dt>
          <dd>{formatSyncTime(repo.lastCommitAt) ?? "—"}</dd>
          <dt className="text-muted-foreground">Last GitHub update</dt>
          <dd>{formatSyncTime(repo.githubUpdatedAt) ?? "—"}</dd>
          <dt className="text-muted-foreground">GitHub</dt>
          <dd>
            <a className="text-primary hover:underline" href={repo.githubUrl ?? `https://github.com/${repo.fullName}`} target="_blank" rel="noreferrer">
              Open repository
            </a>
          </dd>
        </dl>
      </Card>
      <div className="space-y-4">
        <CollectionStatusCard run={lastCollection} busy={busy} onCollect={onCollect} />
        <TopicsCard topics={repo.topics ?? []} />
      </div>
      </div>
      {repo.health && <RepositoryHealthSection health={repo.health} />}
    </div>
  );
}

function TopicsCard({ topics }: { topics: string[] }) {
  return (
    <Card>
      <h2 className="mb-3 font-medium">Topics</h2>
      {topics.length === 0 ? (
        <p className="text-sm text-muted-foreground">No topics yet.</p>
      ) : (
        <div className="flex flex-wrap gap-2">
          {topics.map((name) => (
            <span
              key={name}
              className="inline-flex rounded-full bg-[#ddf4ff] px-2.5 py-0.5 text-xs font-medium text-[#0969da]"
            >
              {name}
            </span>
          ))}
        </div>
      )}
    </Card>
  );
}

function HealthScoreRow({ health }: { health: RepositoryHealth }) {
  return (
    <div className="grid gap-4 md:grid-cols-2">
      <HealthScoreCard title="Discoverability" items={health.discoverability} />
      <HealthScoreCard title="Community Standards" items={health.communityStandards} />
    </div>
  );
}

function HealthScoreCard({ title, items }: { title: string; items: { label: string; passed: boolean }[] }) {
  const passed = items.filter((item) => item.passed).length;
  const percent = items.length === 0 ? 0 : Math.round((passed / items.length) * 100);
  return (
    <Card>
      <div className="text-sm text-muted-foreground">{title}</div>
      <div className="mt-1 text-2xl font-semibold">
        {passed} / {items.length}
      </div>
      <div className="mt-3 h-2 overflow-hidden rounded-full bg-muted">
        <div className="h-full rounded-full bg-emerald-500" style={{ width: `${percent}%` }} />
      </div>
    </Card>
  );
}

function RepositoryHealthSection({ health }: { health: RepositoryHealth }) {
  return (
    <Card>
      <h2 className="mb-4 font-medium">Repository Health</h2>
      <div className="grid items-start gap-8 md:grid-cols-2">
        <HealthCheckList title="Discoverability" items={health.discoverability} />
        <HealthCheckList title="Community Standards" items={health.communityStandards} />
      </div>
    </Card>
  );
}

function HealthCheckList({ title, items }: { title: string; items: { label: string; passed: boolean }[] }) {
  return (
    <div>
      <h3 className="mb-3 text-sm font-medium">{title}</h3>
      <div className="space-y-2 text-sm">
        {items.map((item) => (
          <div key={item.label} className={item.passed ? "text-emerald-700" : "text-muted-foreground"}>
            <span className="mr-2">{item.passed ? "✓" : "○"}</span>
            <span>{item.label}</span>
          </div>
        ))}
      </div>
    </div>
  );
}

function CollectionStatusCard({
  run,
  busy,
  onCollect,
}: {
  run?: CollectionRun;
  busy: boolean;
  onCollect: () => void;
}) {
  if (!run) {
    return (
      <Card>
        <h2 className="mb-4 font-medium">Collection Status</h2>
        <h3 className="font-medium">No collection data yet.</h3>
        <p className="mt-2 text-sm text-muted-foreground">
          Run your first collection to fetch GitHub repository statistics.
        </p>
        <Button className="mt-4" disabled={busy} onClick={onCollect}>
          {busy ? "Collecting..." : "Collect now"}
        </Button>
      </Card>
    );
  }

  if (run.status === "FAILED") {
    const failed = run.jobs.find((job) => job.status === "FAILED");
    return (
      <Card>
        <h2 className="mb-4 font-medium">Collection Status</h2>
        <h3 className="font-medium text-red-700">Collection failed</h3>
        <p className="mt-2 text-sm text-muted-foreground">Last attempt: {formatSyncTime(run.completedAt ?? run.createdAt) ?? "—"}</p>
        {failed?.errorMessage && <p className="mt-2 text-sm">{failed.errorMessage}</p>}
        <Button className="mt-4" disabled={busy} onClick={onCollect}>
          {busy ? "Collecting..." : "Retry"}
        </Button>
      </Card>
    );
  }

  return (
    <Card>
      <h2 className="mb-4 font-medium">Collection Status</h2>
      <div className="text-sm">
        {formatBusinessDate(run.businessDate)} · {run.status}
      </div>
      <div className="mt-1 text-sm text-muted-foreground">
        {run.successfulJobs} / {run.plannedJobs} successful
      </div>
      {(run.completedAt || run.createdAt) && (
        <div className="mt-1 text-sm text-muted-foreground">Last collection: {formatSyncTime(run.completedAt ?? run.createdAt)}</div>
      )}
      <div className="mt-4 space-y-2 text-sm">
        {run.jobs.map((job) => (
          <JobRow key={job.jobType} job={job} />
        ))}
      </div>
    </Card>
  );
}

function JobRow({ job }: { job: CollectionJob }) {
  const icon = job.status === "SUCCESS" ? "✓" : job.status === "FAILED" ? "✕" : job.status === "RUNNING" ? "⟳" : "○";
  const color =
    job.status === "SUCCESS"
      ? "text-emerald-700"
      : job.status === "FAILED"
        ? "text-red-700"
        : job.status === "RUNNING"
          ? "text-primary"
          : "text-muted-foreground";
  return (
    <div className={cn("group relative flex items-center justify-between gap-3", color)}>
      <span>
        {icon} {JOB_LABELS[job.jobType] ?? job.jobType}
      </span>
      {job.status === "FAILED" && (
        <span className="invisible absolute left-0 top-6 z-10 w-64 rounded-md border bg-card p-3 text-xs font-normal text-foreground shadow-lg group-hover:visible">
          <div className="mb-1 font-medium">{JOB_LABELS[job.jobType] ?? job.jobType}</div>
          <div>{job.errorMessage ?? "Collection failed"}</div>
          {job.completedAt && (
            <div className="mt-1 text-muted-foreground">Last attempt: {formatJobTime(job.completedAt)}</div>
          )}
        </span>
      )}
    </div>
  );
}

function GrowthEventsTab({
  repositoryId,
  period,
  onPeriod,
}: {
  repositoryId: number;
  period: Period;
  onPeriod: (period: Period) => void;
}) {
  const [events, setEvents] = useState<GrowthEvent[]>([]);

  const loadEvents = useCallback(() => {
    api<GrowthEvent[]>(`/api/v1/repositories/${repositoryId}/growth-events?period=${period}`)
      .then(setEvents)
      .catch(() => setEvents([]));
  }, [repositoryId, period]);

  useEffect(() => {
    loadEvents();
  }, [loadEvents]);

  return (
    <div className="space-y-4">
      <div className="flex justify-end">
        <PeriodSelector period={period} onPeriod={onPeriod} />
      </div>
      <GrowthEventsPanel repositoryId={repositoryId} events={events} onChanged={loadEvents} />
      <GrowthEventSettingsCard repositoryId={repositoryId} />
    </div>
  );
}

function TrafficPanel({
  repositoryId,
  traffic,
  period,
  onPeriod,
}: {
  repositoryId: number;
  traffic: RepositoryTraffic;
  period: Period;
  onPeriod: (period: Period) => void;
}) {
  const [events, setEvents] = useState<GrowthEvent[]>([]);
  const [filter, setFilter] = useState<EventFilter>("all");
  const [markerEvents, setMarkerEvents] = useState<GrowthEvent[] | null>(null);

  const loadEvents = useCallback(() => {
    api<GrowthEvent[]>(`/api/v1/repositories/${repositoryId}/growth-events?period=${period}`)
      .then(setEvents)
      .catch(() => setEvents([]));
  }, [repositoryId, period]);

  useEffect(() => {
    loadEvents();
  }, [loadEvents]);

  const visibleEvents = useMemo(() => filterGrowthEvents(events, filter), [events, filter]);
  const option = useMemo(() => trafficChartOption(traffic.traffic, visibleEvents), [traffic, visibleEvents]);

  return (
    <div className="space-y-4">
      <div className="flex justify-end">
        <PeriodSelector period={period} onPeriod={onPeriod} />
      </div>
      <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
        <Kpi label="Visitors" value={traffic.totals.uniqueVisitors} />
        <Kpi label="Views" value={traffic.totals.views} />
        <Kpi label="Clones" value={traffic.totals.clones} />
        <Kpi label="Unique Cloners" value={traffic.totals.uniqueCloners} />
      </div>
      <Card>
        <div className="mb-3 flex flex-wrap items-center justify-between gap-3">
          <h2 className="font-medium">Traffic</h2>
          <div className="inline-flex rounded-lg border bg-muted p-1">
            {([
              ["all", "All"],
              ["github", "GitHub"],
              ["promotion", "Promotion"],
            ] as const).map(([key, label]) => (
              <button
                key={key}
                type="button"
                className={cn(
                  "rounded-md px-3 py-1.5 text-sm font-medium",
                  filter === key ? "bg-card text-foreground shadow-sm" : "text-muted-foreground hover:text-foreground",
                )}
                onClick={() => setFilter(key)}
              >
                {label}
              </button>
            ))}
          </div>
        </div>
        <PersistentECharts
          chartId={repoTrafficChartId(repositoryId)}
          series={TRAFFIC_SERIES}
          option={option}
          style={{ height: 360, width: "100%" }}
          onEvents={{
            click: (params: { componentType?: string; data?: { events?: GrowthEvent[] } }) => {
              const clicked = markLineEvents(params);
              if (clicked.length > 0) {
                setMarkerEvents(clicked);
              }
            },
          }}
        />
      </Card>
      <ReferrerTrafficPanel
        repositoryId={repositoryId}
        period={period}
        referrers={traffic.referrers}
        paths={traffic.paths}
      />
      <div className="text-xs text-muted-foreground">
        <div>
          <span className="font-medium text-foreground">About data.</span> Data is collected once per day. Referrer
          traffic shows daily delta between snapshots.
        </div>
        {(traffic.lastCollection?.completedAt ?? traffic.lastCollection?.createdAt) && (
          <div className="mt-1">
            Last updated: {formatSyncTime(traffic.lastCollection?.completedAt ?? traffic.lastCollection?.createdAt)}
          </div>
        )}
      </div>
      {markerEvents && (
        <EventDetailsDialog
          events={markerEvents}
          onClose={() => setMarkerEvents(null)}
          onEdit={() => setMarkerEvents(null)}
          onChanged={() => {
            setMarkerEvents(null);
            loadEvents();
          }}
        />
      )}
    </div>
  );
}

function Kpi({ label, value }: { label: string; value: number }) {
  return (
    <Card>
      <div className="text-sm text-muted-foreground">{label}</div>
      <div className="mt-2 text-2xl font-semibold">{formatNumber(value)}</div>
    </Card>
  );
}

type QuerySortKey = "name" | "rank" | "change" | "change7d" | "change30d" | "best" | "results" | "updated";

function normalizeSearchQuery(value: string) {
  return value.trim().replace(/\s+/g, " ").toLowerCase();
}

function SearchPanel({
  repositoryId,
  visibility,
  queryText,
  setQueryText,
  queryError,
  runningQueryId,
  runningAll,
  onCreate,
  onRun,
  onRunAll,
  onDelete,
}: {
  repositoryId: number;
  visibility: SearchHistory[];
  queryText: string;
  setQueryText: (value: string) => void;
  queryError: string | null;
  runningQueryId: number | null;
  runningAll: boolean;
  onCreate: () => void;
  onRun: (id: number) => void;
  onRunAll: () => void;
  onDelete: (id: number) => void;
}) {
  const [hoveredQueryId, setHoveredQueryId] = useState<number | null>(null);
  const [sortKey, setSortKey] = useState<QuerySortKey | null>(null);
  const [sortDir, setSortDir] = useState<"asc" | "desc">("asc");

  function toggle(key: QuerySortKey) {
    if (sortKey === key) {
      setSortDir((current) => (current === "desc" ? "asc" : "desc"));
      return;
    }
    setSortKey(key);
    setSortDir(key === "name" || key === "rank" || key === "best" ? "asc" : "desc");
  }

  const sorted = useMemo(() => {
    if (!sortKey) {
      return visibility;
    }
    const copy = [...visibility];
    copy.sort((left, right) => {
      const compared = compareQueryRows(left, right, sortKey, sortDir);
      if (compared !== 0) {
        return compared;
      }
      return left.query.id - right.query.id;
    });
    return copy;
  }, [visibility, sortKey, sortDir]);

  const duplicate = visibility.some(
    (item) =>
      normalizeSearchQuery(item.query.query) === normalizeSearchQuery(queryText) && normalizeSearchQuery(queryText) !== "",
  );
  const searchBusy =
    runningAll
    || runningQueryId != null
    || visibility.some(
      (item) => item.searchStatus === "RUNNING" || item.searchStatus === "READY" || item.searchStatus === "RETRY",
    );

  return (
    <div className="space-y-4">
      <Card>
        <h2 className="mb-3 font-medium">Search Queries</h2>
        <div className="mb-4">
          <div className="flex gap-2">
            <input
              className="min-w-0 flex-1 rounded-md border px-3 py-2 text-sm"
              placeholder="transactional outbox language:java"
              value={queryText}
              onChange={(event) => setQueryText(event.target.value)}
            />
            <Button disabled={!queryText.trim() || duplicate} onClick={onCreate}>
              Add query
            </Button>
            <Button
              className="ml-auto shrink-0 bg-muted text-foreground"
              disabled={visibility.length === 0 || searchBusy}
              onClick={onRunAll}
            >
              {searchBusy ? "Running..." : "Run all"}
            </Button>
          </div>
          {(queryError || duplicate) && (
            <p className="mt-2 text-sm text-red-700">
              {queryError ?? "This search query is already tracked for the repository"}
            </p>
          )}
        </div>
        <table className="w-full text-sm">
          <thead>
            <tr className="text-left text-muted-foreground">
              <QuerySortHeader
                label="Search Query"
                align="left"
                className="py-2 pr-4"
                active={sortKey === "name"}
                dir={sortDir}
                onClick={() => toggle("name")}
              />
              <QuerySortHeader label="Rank" active={sortKey === "rank"} dir={sortDir} onClick={() => toggle("rank")} />
              <QuerySortHeader label="Change" active={sortKey === "change"} dir={sortDir} onClick={() => toggle("change")} />
              <QuerySortHeader label="7d" active={sortKey === "change7d"} dir={sortDir} onClick={() => toggle("change7d")} />
              <QuerySortHeader label="30d" active={sortKey === "change30d"} dir={sortDir} onClick={() => toggle("change30d")} />
              <QuerySortHeader label="Best" active={sortKey === "best"} dir={sortDir} onClick={() => toggle("best")} />
              <QuerySortHeader label="Results" active={sortKey === "results"} dir={sortDir} onClick={() => toggle("results")} />
              <QuerySortHeader label="Updated" active={sortKey === "updated"} dir={sortDir} onClick={() => toggle("updated")} />
              <th className="py-2 pl-3" />
            </tr>
          </thead>
          <tbody>
            {sorted.map((item) => {
              const running =
                runningQueryId === item.query.id
                || item.searchStatus === "RUNNING"
                || item.searchStatus === "READY"
                || item.searchStatus === "RETRY";
              const rankChange = formatQueryRankChange(item.change);
              return (
                <tr
                  key={item.query.id}
                  className={cn("border-t", hoveredQueryId === item.query.id && "bg-muted/60")}
                  onMouseEnter={() => setHoveredQueryId(item.query.id)}
                  onMouseLeave={() => setHoveredQueryId(null)}
                >
                  <td className="py-3 pr-4">
                    <Link
                      className="text-primary hover:underline"
                      to={`/repositories/${repositoryId}/search-queries/${item.query.id}`}
                    >
                      {item.query.name}
                    </Link>
                  </td>
                  <td className="px-3 py-3 text-right">{formatRank(item.currentRank, item.query.resultLimit)}</td>
                  <td className={cn("whitespace-nowrap px-3 py-3 text-right font-medium", growthClass(rankChange.direction))}>
                    {rankChange.label}
                  </td>
                  <td className="px-3 py-3 text-right">{formatDelta(item.change7d)}</td>
                  <td className="px-3 py-3 text-right">{formatDelta(item.change30d)}</td>
                  <td className="px-3 py-3 text-right">{formatRank(item.bestRank, item.query.resultLimit)}</td>
                  <td className="px-3 py-3 text-right">{formatNumber(item.totalResults)}</td>
                  <td className="whitespace-nowrap px-3 py-3 text-right text-muted-foreground">
                    {formatSyncTime(item.lastChecked) ?? "—"}
                  </td>
                  <td className="py-3 pl-3">
                    <div className="flex justify-end gap-2">
                      <Button
                        className="bg-muted text-foreground"
                        disabled={running || searchBusy}
                        onClick={() => onRun(item.query.id)}
                      >
                        {running || runningAll ? "Running..." : "Run"}
                      </Button>
                      <Button
                        className="bg-muted text-red-700"
                        disabled={running}
                        onClick={() => onDelete(item.query.id)}
                      >
                        Delete
                      </Button>
                    </div>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </Card>
      <CombinedRankChart repositoryId={repositoryId} visibility={visibility} highlightQueryId={hoveredQueryId} />
    </div>
  );
}

function QuerySortHeader({
  label,
  active,
  dir,
  align = "right",
  className,
  onClick,
}: {
  label: string;
  active: boolean;
  dir: "asc" | "desc";
  align?: "left" | "right";
  className?: string;
  onClick: () => void;
}) {
  return (
    <th className={cn(align === "right" ? "px-3 py-2 text-right" : "", className)}>
      <button
        type="button"
        className={cn("font-medium hover:text-foreground", active && "text-foreground")}
        onClick={onClick}
      >
        {label}
        {active ? (dir === "desc" ? " ↓" : " ↑") : ""}
      </button>
    </th>
  );
}

function compareQueryRows(left: SearchHistory, right: SearchHistory, key: QuerySortKey, dir: "asc" | "desc"): number {
  if (key === "name") {
    const compared = left.query.name.localeCompare(right.query.name, undefined, { sensitivity: "base" });
    return dir === "desc" ? -compared : compared;
  }
  const leftValue = querySortValue(left, key);
  const rightValue = querySortValue(right, key);
  if (leftValue === null && rightValue === null) {
    return 0;
  }
  if (leftValue === null) {
    return 1;
  }
  if (rightValue === null) {
    return -1;
  }
  return dir === "desc" ? rightValue - leftValue : leftValue - rightValue;
}

function queryChangeSortValue(item: SearchHistory): number | null {
  const change = item.change;
  if (!change || change.kind === "NONE") {
    return null;
  }
  if (change.kind === "UNCHANGED") {
    return 0;
  }
  if (change.kind === "IMPROVED") {
    return change.amount;
  }
  if (change.kind === "DECLINED") {
    return -change.amount;
  }
  if (change.kind === "ENTERED") {
    return change.rank == null ? 1000 : 1000 - change.rank;
  }
  return -(1000 + change.amount);
}

function querySortValue(item: SearchHistory, key: Exclude<QuerySortKey, "name">): number | null {
  if (key === "rank") {
    return item.currentRank;
  }
  if (key === "change") {
    return queryChangeSortValue(item);
  }
  if (key === "best") {
    return item.bestRank;
  }
  if (key === "results") {
    return item.totalResults ?? null;
  }
  if (key === "updated") {
    return item.lastChecked ? Date.parse(item.lastChecked) : null;
  }
  if (key === "change7d") {
    return item.change7d;
  }
  return item.change30d;
}

function CombinedRankChart({
  repositoryId,
  visibility,
  highlightQueryId,
}: {
  repositoryId: number;
  visibility: SearchHistory[];
  highlightQueryId: number | null;
}) {
  const option = useMemo(() => {
    const dates = datesFromHistory(visibility.map((item) => item.points));
    return rankHistoryOption({
      dates,
      legend: true,
      series: visibility.map((item) => ({
        name: item.query.name,
        points: item.points,
        limit: item.query.resultLimit,
        highlighted: highlightQueryId === item.query.id,
        dimmed: highlightQueryId != null && highlightQueryId !== item.query.id,
      })),
    });
  }, [visibility, highlightQueryId]);

  const series = useMemo(
    () => visibility.map((item) => ({ key: String(item.query.id), name: item.query.name })),
    [visibility],
  );

  if (visibility.length === 0) {
    return null;
  }
  return (
    <Card>
      <h2 className="mb-3 font-medium">Rank History</h2>
      <PersistentECharts
        chartId={repoSearchChartId(repositoryId)}
        series={series}
        option={option}
        style={{ height: 360, width: "100%" }}
      />
    </Card>
  );
}

function formatBusinessDate(value: string) {
  return new Intl.DateTimeFormat("en-US", { month: "short", day: "numeric", year: "numeric" }).format(
    new Date(`${value}T00:00:00`),
  );
}

function formatJobTime(iso: string) {
  return new Intl.DateTimeFormat("en-US", { hour: "2-digit", minute: "2-digit", hour12: false }).format(new Date(iso));
}
