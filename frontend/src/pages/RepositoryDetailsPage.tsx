import { useCallback, useEffect, useMemo, useState } from "react";
import { Link, useParams, useSearchParams } from "react-router-dom";
import {
  api,
  type CollectionJob,
  type CollectionRun,
  type Repository,
  type RepositoryHealth,
  type RepositoryTraffic,
  type SearchHistory,
} from "../lib/api";
import { cn, formatDelta, formatNumber, formatRank, formatSyncTime } from "../lib/utils";
import { datesFromHistory, rankHistoryOption } from "../lib/rankChart";
import { Button, Card, Skeleton } from "../components/ui";
import { PageBreadcrumb } from "../components/PageBreadcrumb";
import { PeriodSelector, usePeriod, type Period } from "../components/PeriodSelector";
import { PersistentECharts } from "../components/PersistentECharts";
import { pruneChartSelection, repoSearchChartId, repoTrafficChartId, TRAFFIC_SERIES } from "../lib/chartLegend";

type Tab = "overview" | "traffic" | "search";

const TAB_LABEL: Record<Tab, string> = {
  traffic: "Traffic",
  search: "Search Visibility",
  overview: "Overview",
};

const JOB_LABELS: Record<string, string> = {
  TRAFFIC: "Traffic",
  REFERRERS: "Referrers",
  POPULAR_PATHS: "Popular Paths",
  REPOSITORY_STATS: "Repository Stats",
};

export function RepositoryDetailsPage() {
  const { id } = useParams();
  const [searchParams, setSearchParams] = useSearchParams();
  const tabParam = searchParams.get("tab");
  const tab: Tab = tabParam === "overview" || tabParam === "search" ? tabParam : "traffic";
  const [period, setPeriod] = usePeriod(id);
  const [repo, setRepo] = useState<Repository | null>(null);
  const [traffic, setTraffic] = useState<RepositoryTraffic | null>(null);
  const [visibility, setVisibility] = useState<SearchHistory[]>([]);
  const [queryText, setQueryText] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [collecting, setCollecting] = useState(false);
  const [runningQueryId, setRunningQueryId] = useState<number | null>(null);

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
    if (!id || !queryText.trim()) {
      return;
    }
    await api(`/api/v1/repositories/${id}/search-queries`, {
      method: "POST",
      body: JSON.stringify({ name: queryText, query: queryText, enabled: true, resultLimit: 50 }),
    });
    setQueryText("");
    await load();
  }

  async function runQuery(queryId: number) {
    if (runningQueryId != null) {
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
        <div className="grid grid-cols-1 gap-x-4 gap-y-2 sm:grid-cols-[minmax(0,1fr)_auto]">
          <Skeleton className="h-7 w-72" />
          <Skeleton className="h-9 w-28 sm:justify-self-end" />
          <Skeleton className="h-4 w-64 sm:col-start-1" />
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
      <header className="grid grid-cols-1 items-start gap-x-4 gap-y-2 sm:grid-cols-[minmax(0,1fr)_auto]">
        <div className="min-w-0 sm:col-start-1 sm:row-start-1">
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
            <p className="mt-1 text-sm font-normal text-muted-foreground">{repo.description}</p>
          )}
        </div>
        <Button
          className="justify-self-start sm:col-start-2 sm:row-start-1 sm:justify-self-end"
          disabled={busy}
          onClick={() => void collect()}
        >
          {busy ? "Collecting..." : "Collect now"}
        </Button>
      </header>
      {repo.health && <HealthScoreRow health={repo.health} />}
      <div className="inline-flex rounded-lg border bg-muted p-1">
        {([
          ["traffic", "Traffic"],
          ["search", "Search Visibility"],
          ["overview", "Overview"],
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
      {tab === "traffic" && (
        <TrafficPanel repositoryId={repo.id} traffic={traffic} period={period} onPeriod={setPeriod} />
      )}
      {tab === "search" && (
        <SearchPanel
          repositoryId={repo.id}
          visibility={visibility}
          queryText={queryText}
          setQueryText={setQueryText}
          runningQueryId={runningQueryId}
          onCreate={() => void createQuery()}
          onRun={runQuery}
          onDelete={(queryId) => void deleteQuery(queryId)}
        />
      )}
    </div>
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
  const option = useMemo(
    () => ({
      tooltip: {
        trigger: "axis",
        formatter: (params: { axisValue: string; seriesName: string; data: number | null }[]) => {
          if (!Array.isArray(params) || params.length === 0) {
            return "";
          }
          const date = new Intl.DateTimeFormat("en-US", { month: "short", day: "numeric" }).format(
            new Date(`${params[0].axisValue}T00:00:00`),
          );
          const rows = params
            .map((item) => {
              const value = item.data === null || item.data === undefined ? "—" : formatNumber(item.data);
              return `<div style="display:flex;justify-content:space-between;gap:24px"><span>${item.seriesName}</span><span>${value}</span></div>`;
            })
            .join("");
          return `<div style="min-width:160px"><div style="margin-bottom:6px">${date}</div>${rows}</div>`;
        },
      },
      legend: { data: ["Views", "Visitors", "Clones"] },
      grid: { left: 16, right: 16, top: 40, bottom: 24, containLabel: true },
      xAxis: { type: "category", data: traffic.traffic.map((point) => point.date), boundaryGap: false },
      yAxis: { type: "value" },
      series: [
        { name: "Views", type: "line", connectNulls: false, data: traffic.traffic.map((point) => point.views ?? null) },
        { name: "Visitors", type: "line", connectNulls: false, data: traffic.traffic.map((point) => point.uniqueVisitors ?? null) },
        { name: "Clones", type: "line", connectNulls: false, data: traffic.traffic.map((point) => point.clones ?? null) },
      ],
    }),
    [traffic],
  );

  return (
    <div className="space-y-4">
      <div className="flex justify-end">
        <PeriodSelector period={period} onPeriod={onPeriod} />
      </div>
      <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
        <Kpi label="Views" value={traffic.totals.views} />
        <Kpi label="Unique Visitors" value={traffic.totals.uniqueVisitors} />
        <Kpi label="Clones" value={traffic.totals.clones} />
        <Kpi label="Unique Cloners" value={traffic.totals.uniqueCloners} />
      </div>
      <Card>
        <h2 className="mb-3 font-medium">Traffic</h2>
        <PersistentECharts
          chartId={repoTrafficChartId(repositoryId)}
          series={TRAFFIC_SERIES}
          option={option}
          style={{ height: 360, width: "100%" }}
        />
      </Card>
      <div className="grid gap-4 md:grid-cols-2">
        <Card>
          <h2 className="mb-3 font-medium">Referrers</h2>
          <MetricTable
            rows={traffic.referrers.map((row) => ({ key: row.referrer, title: row.referrer, views: row.views, visitors: row.uniqueVisitors }))}
          />
        </Card>
        <Card>
          <h2 className="mb-3 font-medium">Popular Paths</h2>
          <MetricTable
            rows={traffic.paths.map((row) => ({
              key: row.path,
              title: row.path,
              subtitle: row.title,
              views: row.views,
              visitors: row.uniqueVisitors,
            }))}
          />
        </Card>
      </div>
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

function MetricTable({
  rows,
}: {
  rows: { key: string; title: string; subtitle?: string; views: number; visitors: number }[];
}) {
  return (
    <table className="w-full text-sm">
      <thead>
        <tr className="text-left text-muted-foreground">
          <th>Source</th>
          <th className="text-right">Views</th>
          <th className="text-right">Visitors</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((row) => (
          <tr key={row.key} className="border-t">
            <td className="py-2">
              <div>{row.title}</div>
              {row.subtitle && <div className="text-xs text-muted-foreground">{row.subtitle}</div>}
            </td>
            <td className="text-right">{formatNumber(row.views)}</td>
            <td className="text-right">{formatNumber(row.visitors)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

type QuerySortKey = "name" | "rank" | "change7d" | "change30d" | "best" | "results";

function SearchPanel({
  repositoryId,
  visibility,
  queryText,
  setQueryText,
  runningQueryId,
  onCreate,
  onRun,
  onDelete,
}: {
  repositoryId: number;
  visibility: SearchHistory[];
  queryText: string;
  setQueryText: (value: string) => void;
  runningQueryId: number | null;
  onCreate: () => void;
  onRun: (id: number) => void;
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

  return (
    <div className="space-y-4">
      <Card>
        <h2 className="mb-3 font-medium">Search Queries</h2>
        <div className="mb-4 flex gap-2">
          <input
            className="flex-1 rounded-md border px-3 py-2 text-sm"
            placeholder="transactional outbox language:java"
            value={queryText}
            onChange={(event) => setQueryText(event.target.value)}
          />
          <Button onClick={onCreate}>Add query</Button>
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
              <QuerySortHeader label="7d" active={sortKey === "change7d"} dir={sortDir} onClick={() => toggle("change7d")} />
              <QuerySortHeader label="30d" active={sortKey === "change30d"} dir={sortDir} onClick={() => toggle("change30d")} />
              <QuerySortHeader label="Best" active={sortKey === "best"} dir={sortDir} onClick={() => toggle("best")} />
              <QuerySortHeader label="Results" active={sortKey === "results"} dir={sortDir} onClick={() => toggle("results")} />
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
                  <td className="px-3 py-3 text-right">{formatDelta(item.change7d)}</td>
                  <td className="px-3 py-3 text-right">{formatDelta(item.change30d)}</td>
                  <td className="px-3 py-3 text-right">{formatRank(item.bestRank, item.query.resultLimit)}</td>
                  <td className="px-3 py-3 text-right">{formatNumber(item.totalResults)}</td>
                  <td className="py-3 pl-3">
                    <div className="flex justify-end gap-2">
                      <Button
                        className="bg-muted text-foreground"
                        disabled={running}
                        onClick={() => onRun(item.query.id)}
                      >
                        {running ? "Running..." : "Run"}
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

function querySortValue(item: SearchHistory, key: Exclude<QuerySortKey, "name">): number | null {
  if (key === "rank") {
    return item.currentRank;
  }
  if (key === "best") {
    return item.bestRank;
  }
  if (key === "results") {
    return item.totalResults ?? null;
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
