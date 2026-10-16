import { useCallback, useEffect, useMemo, useState } from "react";
import { Link, useParams, useSearchParams } from "react-router-dom";
import { ExternalLink } from "lucide-react";
import {
  api,
  type CollectionJob,
  type CollectionRun,
  type GrowthEvent,
  type Repository,
  type RepositoryHealth,
  type RepositoryTraffic,
  type SearchHistory,
  type TopicHistory,
} from "../lib/api";
import { JOB_LABELS } from "../lib/collectProgress";
import { loadGitHubAccess, type TokenState } from "../lib/githubAccess";
import { cn, formatDelta, formatNumber, formatQueryRankChange, formatRank, formatSyncTime, growthClass } from "../lib/utils";
import { datesFromHistory, rankHistoryOption } from "../lib/rankChart";
import { Button, Card, Skeleton } from "../components/ui";
import { PageBreadcrumb } from "../components/PageBreadcrumb";
import { RepositoryAccessNote } from "../components/RepositoryAccessNote";
import { PeriodSelector, usePeriod, type Period } from "../components/PeriodSelector";
import { TrafficKpiCard } from "../components/TrafficKpiCard";
import { ChartCard } from "../components/ChartDownloadMenu";
import { PersistentECharts } from "../components/PersistentECharts";
import { RepositoryStatsPanel } from "../components/RepositoryStatsPanel";
import { SnapshotCards } from "../components/SnapshotCards";
import { EventDetailsDialog, GrowthEventsPanel } from "../components/GrowthEventsPanel";
import { GrowthEventSettingsCard } from "../components/GrowthEventSettingsCard";
import { pruneChartSelection, repoSearchChartId, repoTopicsChartId, repoTrafficChartId, REPO_TRAFFIC_SERIES } from "../lib/chartLegend";
import { filterGrowthEvents, type EventFilter } from "../lib/growthEvents";
import { markLineEvents, trafficChartOption } from "../lib/trafficChart";
import { useServicePaths } from "../lib/servicePathPrefs";
import { QUERY_SORT_KEYS, compareQueryRows, sortTopicHistories } from "../lib/querySort";
import { useTableSort } from "../lib/tableSortPrefs";

type Tab = "overview" | "traffic" | "stats" | "search" | "topics" | "growth-events";
type TopicScope = "all" | "language";

const TAB_LABEL: Record<Tab, string> = {
  traffic: "Traffic",
  stats: "Stats",
  search: "Search Visibility",
  topics: "Topics Visibility",
  overview: "Overview",
  "growth-events": "Growth Events",
};

function parseScope(value: string | null): TopicScope {
  return value === "language" ? "language" : "all";
}

function parseTab(tabParam: string | null): Tab {
  if (
    tabParam === "overview"
    || tabParam === "stats"
    || tabParam === "search"
    || tabParam === "topics"
    || tabParam === "growth-events"
  ) {
    return tabParam;
  }
  if (tabParam === "settings") {
    return "growth-events";
  }
  return "traffic";
}

export function RepositoryDetailsPage() {
  const { id } = useParams();
  const [searchParams, setSearchParams] = useSearchParams();
  const tabParam = searchParams.get("tab");
  const tab = parseTab(tabParam);
  const scope = parseScope(searchParams.get("scope"));
  const [period, setPeriod] = usePeriod(id);
  const { showServicePaths, setShowServicePaths } = useServicePaths(id);
  const [repo, setRepo] = useState<Repository | null>(null);
  const [traffic, setTraffic] = useState<RepositoryTraffic | null>(null);
  const [visibility, setVisibility] = useState<SearchHistory[]>([]);
  const [topicsVisibility, setTopicsVisibility] = useState<TopicHistory[]>([]);
  const [queryText, setQueryText] = useState("");
  const [queryError, setQueryError] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [tokenState, setTokenState] = useState<TokenState | null>(null);

  useEffect(() => {
    if (repo?.accessible !== false && !repo?.trafficPermissionMissing) {
      return;
    }
    let cancelled = false;
    void loadGitHubAccess().then((next) => {
      if (!cancelled) {
        setTokenState(next.tokenState);
      }
    });
    return () => {
      cancelled = true;
    };
  }, [repo?.accessible, repo?.trafficPermissionMissing]);
  const [loading, setLoading] = useState(true);
  const [runningQueryId, setRunningQueryId] = useState<number | null>(null);
  const [runningAll, setRunningAll] = useState(false);
  const [focusHealth, setFocusHealth] = useState(false);

  function setTab(next: Tab) {
    if (next === "traffic") {
      setSearchParams({}, { replace: true });
      return;
    }
    if (next === "topics") {
      setSearchParams(scope === "language" ? { tab: "topics", scope: "language" } : { tab: "topics" }, { replace: true });
      return;
    }
    setSearchParams({ tab: next }, { replace: true });
  }

  function setScope(next: TopicScope) {
    setSearchParams(next === "language" ? { tab: "topics", scope: "language" } : { tab: "topics" }, { replace: true });
  }

  const load = useCallback(async () => {
    if (!id) {
      return;
    }
    const [repository, trafficData, searchData, topicData] = await Promise.all([
      api<Repository>(`/api/v1/repositories/${id}`),
      api<RepositoryTraffic>(
        `/api/v1/repositories/${id}/traffic?period=${period}&includeServicePaths=${showServicePaths}`,
      ),
      api<SearchHistory[]>(`/api/v1/repositories/${id}/search-visibility`),
      api<TopicHistory[]>(`/api/v1/repositories/${id}/topics-visibility?scope=${scope}`),
    ]);
    setRepo(repository);
    setTraffic(trafficData);
    setVisibility(searchData);
    setTopicsVisibility(topicData);
  }, [id, period, showServicePaths, scope]);

  useEffect(() => {
    function onCollected() {
      void load().catch((err: Error) => setError(err.message));
    }
    window.addEventListener("repos-collected", onCollected);
    return () => window.removeEventListener("repos-collected", onCollected);
  }, [load]);

  useEffect(() => {
    if (tab !== "overview" || !focusHealth) {
      return;
    }
    document.getElementById("repository-health")?.scrollIntoView({ block: "start" });
    setFocusHealth(false);
  }, [focusHealth, tab]);

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
  const topicsActive = topicsVisibility.some((item) => {
    const status = item.searchStatus;
    return status === "RUNNING" || status === "READY" || status === "RETRY";
  });

  useEffect(() => {
    if (!searchActive && !topicsActive) {
      setRunningQueryId(null);
      setRunningAll(false);
    }
    if (!runActive && !searchActive && !topicsActive) {
      return;
    }
    const timer = window.setInterval(() => {
      void load().catch(() => undefined);
    }, 2500);
    return () => window.clearInterval(timer);
  }, [runActive, searchActive, topicsActive, load]);

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

  async function runTopicWatch(watchId: number) {
    if (runningQueryId != null || runningAll || topicsActive) {
      return;
    }
    setRunningQueryId(watchId);
    try {
      await api(`/api/v1/topic-watches/${watchId}/run`, { method: "POST" });
      await load();
    } finally {
      setRunningQueryId(null);
    }
  }

  async function runAllTopics() {
    if (!id || runningQueryId != null || runningAll || topicsActive || topicsVisibility.length === 0) {
      return;
    }
    setRunningAll(true);
    try {
      await api(`/api/v1/repositories/${id}/topic-watches/run?scope=${scope}`, { method: "POST" });
      await load();
    } catch (err) {
      setError((err as Error).message);
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
          <Skeleton className="h-4 w-72" />
          <div className="flex items-center justify-between gap-4 border-b pb-3">
            <Skeleton className="h-4 w-64" />
            <Skeleton className="h-4 w-28" />
          </div>
        </div>
        <div className="grid gap-4 md:grid-cols-2">
          <Skeleton className="h-64" />
          <Skeleton className="h-64" />
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <header>
        <PageBreadcrumb
          items={[
            { label: "Portfolio", to: "/dashboard" },
            {
              label: repo.fullName,
              repoSwitcher: {
                currentId: repo.id,
                description: repo.description,
                hrefFor: (repositoryId) =>
                  tab === "traffic"
                    ? `/repositories/${repositoryId}`
                    : tab === "topics" && scope === "language"
                      ? `/repositories/${repositoryId}?tab=topics&scope=language`
                      : `/repositories/${repositoryId}?tab=${tab}`,
              },
            },
            { label: TAB_LABEL[tab] },
          ]}
        />
        <RepositoryAccessNote
          accessible={repo.accessible}
          trafficPermissionMissing={repo.trafficPermissionMissing}
          tokenState={tokenState}
        />
        <div className="mt-3 flex flex-wrap items-center gap-x-4 gap-y-2 border-b pb-3 text-sm text-muted-foreground">
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
          {repo.health && (
            <>
              <div className="flex items-center gap-4">
                <StatDivider />
                <HealthScoreLink
                  title="Discoverability"
                  items={repo.health.discoverability}
                  onClick={() => {
                    setFocusHealth(true);
                    setTab("overview");
                  }}
                />
              </div>
              <div className="flex items-center gap-4">
                <StatDivider />
                <HealthScoreLink
                  title="Community Standards"
                  items={repo.health.communityStandards}
                  onClick={() => {
                    setFocusHealth(true);
                    setTab("overview");
                  }}
                />
              </div>
            </>
          )}
          <div className="ml-auto flex items-center gap-4">
            <StatDivider />
            <a
              className="inline-flex items-center gap-1 text-primary hover:underline"
              href={`${repo.githubUrl ?? `https://github.com/${repo.fullName}`}/graphs/traffic`}
              target="_blank"
              rel="noreferrer"
            >
              <ExternalLink className="h-4 w-4 shrink-0" aria-hidden="true" />
              GitHub Traffic
            </a>
          </div>
        </div>
      </header>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="inline-flex rounded-lg border bg-muted p-1">
          {([
            ["traffic", "Traffic"],
            ["stats", "Stats"],
            ["search", "Search Visibility"],
            ["topics", "Topics Visibility"],
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
        {(tab === "traffic" || tab === "stats" || tab === "growth-events") && (
          <PeriodSelector period={period} onPeriod={setPeriod} />
        )}
      </div>
      {tab === "overview" && (
        <Overview repo={repo} lastCollection={traffic.lastCollection} />
      )}
      {tab === "growth-events" && (
        <GrowthEventsTab repositoryId={repo.id} period={period} />
      )}
      {tab === "traffic" && (
        <TrafficPanel
          repositoryId={repo.id}
          traffic={traffic}
          period={period}
          showServicePaths={showServicePaths}
          onShowServicePathsChange={setShowServicePaths}
        />
      )}
      {tab === "stats" && (
        <RepositoryStatsPanel repositoryId={repo.id} period={period} />
      )}
      {tab === "topics" && (
        <TopicsPanel
          repositoryId={repo.id}
          language={repo.language}
          scope={scope}
          onScope={setScope}
          visibility={topicsVisibility}
          runningQueryId={runningQueryId}
          runningAll={runningAll}
          onRun={runTopicWatch}
          onRunAll={() => void runAllTopics()}
        />
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

function StatDivider() {
  return <span className="w-px shrink-0 self-stretch bg-border" aria-hidden="true" />;
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
}: {
  repo: Repository;
  lastCollection?: CollectionRun;
}) {
  const about = repo.description?.trim();
  return (
    <div className="space-y-4">
      {about && (
        <Card>
          <h2 className="mb-4 font-medium">About this repository</h2>
          <p className="text-sm font-normal text-muted-foreground">{about}</p>
        </Card>
      )}
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
        <CollectionStatusCard run={lastCollection} />
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

function HealthScoreLink({
  title,
  items,
  onClick,
}: {
  title: string;
  items: { label: string; passed: boolean }[];
  onClick: () => void;
}) {
  const passed = items.filter((item) => item.passed).length;
  const percent = items.length === 0 ? 0 : Math.round((passed / items.length) * 100);
  return (
    <button type="button" onClick={onClick} className="inline-grid justify-items-stretch gap-1 text-left text-sm text-foreground">
      <span className="whitespace-nowrap">
        {title} {passed}/{items.length}
      </span>
      <span className="h-1 overflow-hidden rounded-full bg-muted" aria-hidden="true">
        <span className="block h-full rounded-full bg-emerald-500" style={{ width: `${percent}%` }} />
      </span>
    </button>
  );
}

function RepositoryHealthSection({ health }: { health: RepositoryHealth }) {
  return (
    <Card id="repository-health">
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

function CollectionStatusCard({ run }: { run?: CollectionRun }) {
  if (!run) {
    return (
      <Card>
        <h2 className="mb-4 font-medium">Collection Status</h2>
        <h3 className="font-medium">No collection data yet.</h3>
        <p className="mt-2 text-sm text-muted-foreground">
          Run your first collection to fetch GitHub repository statistics.
        </p>
      </Card>
    );
  }

  const failed = run.jobs.find((job) => job.status === "FAILED");
  return (
    <Card>
      <h2 className="mb-4 font-medium">Collection Status</h2>
      {run.status === "FAILED" && <h3 className="mb-2 font-medium text-red-700">Collection failed</h3>}
      <div className="text-sm">
        {formatBusinessDate(run.businessDate)} · {run.status}
      </div>
      <div className="mt-1 text-sm text-muted-foreground">
        {run.successfulJobs} / {run.plannedJobs} successful
      </div>
      {(run.completedAt || run.createdAt) && (
        <div className="mt-1 text-sm text-muted-foreground">Last collection: {formatSyncTime(run.completedAt ?? run.createdAt)}</div>
      )}
      {failed?.errorMessage && <p className="mt-2 text-sm">{failed.errorMessage}</p>}
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
}: {
  repositoryId: number;
  period: Period;
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
      <GrowthEventsPanel repositoryId={repositoryId} events={events} onChanged={loadEvents} />
      <GrowthEventSettingsCard repositoryId={repositoryId} />
    </div>
  );
}

function TrafficPanel({
  repositoryId,
  traffic,
  period,
  showServicePaths,
  onShowServicePathsChange,
}: {
  repositoryId: number;
  traffic: RepositoryTraffic;
  period: Period;
  showServicePaths: boolean;
  onShowServicePathsChange: (next: boolean) => void;
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
  const option = useMemo(() => trafficChartOption(traffic.traffic, visibleEvents, period), [traffic, visibleEvents, period]);

  return (
    <div className="space-y-3">
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <TrafficKpiCard
          label="Visitors"
          value={traffic.totals.uniqueVisitors.value}
          growthPercent={traffic.totals.uniqueVisitors.growthPercent}
          period={period}
          series={traffic.traffic.map((point) => point.uniqueVisitors)}
        />
        <TrafficKpiCard
          label="Views"
          value={traffic.totals.views.value}
          growthPercent={traffic.totals.views.growthPercent}
          period={period}
          series={traffic.traffic.map((point) => point.views)}
        />
        <TrafficKpiCard
          label="Clones"
          value={traffic.totals.clones.value}
          growthPercent={traffic.totals.clones.growthPercent}
          period={period}
          series={traffic.traffic.map((point) => point.clones)}
        />
        <TrafficKpiCard
          label="Unique Cloners"
          value={traffic.totals.uniqueCloners.value}
          growthPercent={traffic.totals.uniqueCloners.growthPercent}
          period={period}
          series={traffic.traffic.map((point) => point.uniqueCloners)}
        />
      </div>
      <ChartCard
        title="Traffic"
        controls={
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
        }
      >
        <PersistentECharts
          chartId={repoTrafficChartId(repositoryId)}
          series={REPO_TRAFFIC_SERIES}
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
      </ChartCard>
      <SnapshotCards
        repositoryId={repositoryId}
        referrers={traffic.referrers}
        referrerSnapshotAt={traffic.referrerSnapshotAt}
        paths={traffic.paths}
        pathSnapshotAt={traffic.pathSnapshotAt}
        showServicePaths={showServicePaths}
        onShowServicePathsChange={onShowServicePathsChange}
      />
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

const QUERY_ASC_FIRST_KEYS = ["name", "rank", "best"] as const;

function normalizeSearchQuery(value: string) {
  return value.trim().replace(/\s+/g, " ").toLowerCase();
}

function TopicsPanel({
  repositoryId,
  language,
  scope,
  onScope,
  visibility,
  runningQueryId,
  runningAll,
  onRun,
  onRunAll,
}: {
  repositoryId: number;
  language?: string;
  scope: TopicScope;
  onScope: (scope: TopicScope) => void;
  visibility: TopicHistory[];
  runningQueryId: number | null;
  runningAll: boolean;
  onRun: (id: number) => void;
  onRunAll: () => void;
}) {
  const [hoveredWatchId, setHoveredWatchId] = useState<number | null>(null);
  const { sortKey, sortDir, toggle } = useTableSort({
    tableId: "topic-watches",
    scope: repositoryId,
    keys: QUERY_SORT_KEYS,
    initial: { key: "rank", dir: "asc" },
    ascFirst: QUERY_ASC_FIRST_KEYS,
  });

  const sorted = useMemo(
    () => sortTopicHistories(visibility, sortKey, sortDir),
    [visibility, sortKey, sortDir],
  );

  const searchBusy =
    runningAll
    || runningQueryId != null
    || visibility.some(
      (item) => item.searchStatus === "RUNNING" || item.searchStatus === "READY" || item.searchStatus === "RETRY",
    );
  const languageLabel = language || "Language";
  const scopeQuery = scope === "language" ? "?scope=language" : "";

  return (
    <div className="space-y-4">
      <Card>
        <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
          <h2 className="font-medium">Topics</h2>
          <div className="flex flex-wrap items-center gap-2">
            {language && (
              <div className="inline-flex rounded-lg border bg-muted p-1">
                {(["all", "language"] as const).map((key) => (
                  <button
                    key={key}
                    type="button"
                    className={cn(
                      "rounded-md px-3 py-1.5 text-sm font-medium",
                      scope === key ? "bg-card text-foreground shadow-sm" : "text-muted-foreground hover:text-foreground",
                    )}
                    onClick={() => onScope(key)}
                  >
                    {key === "all" ? "All" : languageLabel}
                  </button>
                ))}
              </div>
            )}
            <Button
              className="bg-muted text-foreground"
              disabled={visibility.length === 0 || searchBusy}
              onClick={onRunAll}
            >
              {searchBusy ? "Running..." : "Run all"}
            </Button>
          </div>
        </div>
        {visibility.length === 0 ? (
          <p className="text-sm text-muted-foreground">
            {language && scope === "language"
              ? "No language-scoped topic watches yet."
              : "No topics yet. Add topics to the repository on GitHub."}
          </p>
        ) : (
          <table className="w-full text-sm">
            <thead>
              <tr className="text-left text-muted-foreground">
                <QuerySortHeader
                  label="Topic"
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
                  runningQueryId === item.watch.id
                  || item.searchStatus === "RUNNING"
                  || item.searchStatus === "READY"
                  || item.searchStatus === "RETRY";
                const rankChange = formatQueryRankChange(item.change);
                return (
                  <tr
                    key={item.watch.id}
                    className={cn("border-t", hoveredWatchId === item.watch.id && "bg-muted/60")}
                    onMouseEnter={() => setHoveredWatchId(item.watch.id)}
                    onMouseLeave={() => setHoveredWatchId(null)}
                  >
                    <td className="py-3 pr-4">
                      <Link
                        className="text-primary hover:underline"
                        to={`/repositories/${repositoryId}/topics/${encodeURIComponent(item.watch.topic)}${scopeQuery}`}
                      >
                        {item.watch.topic}
                      </Link>
                    </td>
                    <td className="px-3 py-3 text-right">{formatRank(item.currentRank, item.watch.resultLimit)}</td>
                    <td className={cn("whitespace-nowrap px-3 py-3 text-right font-medium", growthClass(rankChange.direction))}>
                      {rankChange.label}
                    </td>
                    <td className="px-3 py-3 text-right">{formatDelta(item.change7d)}</td>
                    <td className="px-3 py-3 text-right">{formatDelta(item.change30d)}</td>
                    <td className="px-3 py-3 text-right">{formatRank(item.bestRank, item.watch.resultLimit)}</td>
                    <td className="px-3 py-3 text-right">{formatNumber(item.totalResults)}</td>
                    <td className="whitespace-nowrap px-3 py-3 text-right text-muted-foreground">
                      {formatSyncTime(item.lastChecked) ?? "—"}
                    </td>
                    <td className="py-3 pl-3">
                      <div className="flex justify-end">
                        <Button
                          className="bg-muted text-foreground"
                          disabled={running || searchBusy}
                          onClick={() => onRun(item.watch.id)}
                        >
                          {running || runningAll ? "Running..." : "Run"}
                        </Button>
                      </div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}
      </Card>
      <CombinedTopicRankChart repositoryId={repositoryId} scope={scope} visibility={visibility} highlightWatchId={hoveredWatchId} />
    </div>
  );
}

function CombinedTopicRankChart({
  repositoryId,
  scope,
  visibility,
  highlightWatchId,
}: {
  repositoryId: number;
  scope: TopicScope;
  visibility: TopicHistory[];
  highlightWatchId: number | null;
}) {
  const option = useMemo(() => {
    const dates = datesFromHistory(
      visibility.map((item) => item.points),
      visibility.flatMap((item) => item.missedDates ?? []),
    );
    return rankHistoryOption({
      dates,
      legend: true,
      series: visibility.map((item) => ({
        name: item.watch.topic,
        points: item.points,
        limit: item.watch.resultLimit,
        missedDates: item.missedDates,
        highlighted: highlightWatchId === item.watch.id,
        dimmed: highlightWatchId != null && highlightWatchId !== item.watch.id,
      })),
    });
  }, [visibility, highlightWatchId]);

  const series = useMemo(
    () => visibility.map((item) => ({ key: String(item.watch.id), name: item.watch.topic })),
    [visibility],
  );

  if (visibility.length === 0) {
    return null;
  }
  return (
    <ChartCard title="Rank History">
      <PersistentECharts
        chartId={repoTopicsChartId(repositoryId, scope)}
        series={series}
        option={option}
        style={{ height: 360, width: "100%" }}
      />
    </ChartCard>
  );
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
  const { sortKey, sortDir, toggle } = useTableSort({
    tableId: "search-queries",
    scope: repositoryId,
    keys: QUERY_SORT_KEYS,
    initial: { key: null, dir: "asc" },
    ascFirst: QUERY_ASC_FIRST_KEYS,
  });

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
        <div className="mb-3 flex items-baseline gap-3">
          <h2 className="font-medium">Search Queries</h2>
          <a
            className="text-sm font-normal text-primary underline"
            href="https://docs.github.com/en/search-github/searching-on-github/searching-for-repositories"
            target="_blank"
            rel="noreferrer"
          >
            Search syntax tips
          </a>
        </div>
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
    const dates = datesFromHistory(
      visibility.map((item) => item.points),
      visibility.flatMap((item) => item.missedDates ?? []),
    );
    return rankHistoryOption({
      dates,
      legend: true,
      series: visibility.map((item) => ({
        name: item.query.name,
        points: item.points,
        limit: item.query.resultLimit,
        missedDates: item.missedDates,
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
    <ChartCard title="Rank History">
      <PersistentECharts
        chartId={repoSearchChartId(repositoryId)}
        series={series}
        option={option}
        style={{ height: 360, width: "100%" }}
      />
    </ChartCard>
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
