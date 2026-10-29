import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Link, useParams, useSearchParams } from "react-router-dom";
import {
  Check,
  Circle,
  CircleCheck,
  Clock3,
  Code2,
  ExternalLink,
  Eye,
  GitBranch,
  GitFork,
  Info,
  LockKeyhole,
  Package,
  RefreshCw,
  Search,
  Star,
  Tag,
  UserRound,
  UsersRound,
  type LucideIcon,
} from "lucide-react";
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
import { collectionFailureReason, JOB_LABELS } from "../lib/collectProgress";
import { loadGitHubAccess, type TokenState } from "../lib/githubAccess";
import { useMutationAccess } from "../lib/mutationAccess";
import { cn, formatChartAxisDate, formatDelta, formatNumber, formatQueryRankChange, formatRank, formatSyncTime, growthClass } from "../lib/utils";
import { asGrowthEvents } from "../lib/growthEvents";
import { datesFromHistory, rankHistoryOption, rankLineColor } from "../lib/rankChart";
import { Button, Card, Skeleton } from "../components/ui";
import { PageBreadcrumb } from "../components/PageBreadcrumb";
import { RepositoryAccessNote } from "../components/RepositoryAccessNote";
import { PeriodSelector, usePeriod, type Period } from "../components/PeriodSelector";
import { TrafficKpiCard } from "../components/TrafficKpiCard";
import { ChartCard } from "../components/ChartDownloadMenu";
import { ChartModePlot } from "../components/PersistentECharts";
import { RepositoryStatsPanel } from "../components/RepositoryStatsPanel";
import { SnapshotCards } from "../components/SnapshotCards";
import { GrowthEventsPanel } from "../components/GrowthEventsPanel";
import { GrowthEventSettingsCard } from "../components/GrowthEventSettingsCard";
import { pruneChartSelection, repoSearchChartId, repoTopicsChartId, repoTrafficChartId, REPO_TRAFFIC_SERIES } from "../lib/chartLegend";
import { resolveSearchQuerySelection, writeSearchQuerySelection } from "../lib/searchQuerySelection";
import { SearchVisibilityKpis } from "../components/SearchVisibilityKpis";
import { TopicVisibilityKpis } from "../components/TopicVisibilityKpis";
import { resolveTopicSelection, writeTopicSelection } from "../lib/topicSelection";
import { readTopicScope, resolveVisibleScope, writeTopicScope, type TopicScope } from "../lib/topicScope";
import { trafficChartOption } from "../lib/trafficChart";
import { repositoryGapText, trafficJobWarning } from "../lib/trafficGap";
import { useServicePaths } from "../lib/servicePathPrefs";
import { QUERY_SORT_KEYS, compareQueryRows, sortTopicHistories } from "../lib/querySort";
import { useTableSort } from "../lib/tableSortPrefs";

type Tab = "overview" | "traffic" | "stats" | "search" | "topics" | "growth-events";

const TAB_LABEL: Record<Tab, string> = {
  traffic: "Traffic",
  stats: "Stats",
  search: "Search Visibility",
  topics: "Topics Visibility",
  overview: "Overview",
  "growth-events": "Growth Events",
};

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
  const { allowMutations } = useMutationAccess();
  const tabParam = searchParams.get("tab");
  const tab = parseTab(tabParam);
  const scopeQuery = searchParams.get("scope");
  const [period, setPeriod] = usePeriod(id);
  const { showServicePaths, setShowServicePaths } = useServicePaths(id);
  const [repo, setRepo] = useState<Repository | null>(null);
  const storedScope = readTopicScope(Number(id));
  const scope: TopicScope = tab === "topics"
    ? resolveVisibleScope(scopeQuery, storedScope, Boolean(repo?.language))
    : "all";
  const [traffic, setTraffic] = useState<RepositoryTraffic | null>(null);
  const [visibility, setVisibility] = useState<SearchHistory[]>([]);
  const [plainTopics, setPlainTopics] = useState<TopicHistory[]>([]);
  const [languageTopics, setLanguageTopics] = useState<TopicHistory[]>([]);
  const topicsVisibility = scope === "language" ? languageTopics : plainTopics;
  const knownTopics = [...plainTopics, ...languageTopics].filter(
    (item, index, list) => list.findIndex((other) => other.watch.topic === item.watch.topic) === index,
  );
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
  const scopeIntent = useRef<TopicScope | null>(null);
  const [scopeChoice, setScopeChoice] = useState(0);

  function setTab(next: Tab) {
    if (next === "traffic") {
      setSearchParams({}, { replace: true });
      return;
    }
    if (next === "topics") {
      const nextScope = resolveVisibleScope(null, readTopicScope(Number(id)), Boolean(repo?.language));
      setSearchParams(nextScope === "language" ? { tab: "topics", scope: "language" } : { tab: "topics" }, { replace: true });
      return;
    }
    setSearchParams({ tab: next }, { replace: true });
  }

  function setScope(next: TopicScope) {
    scopeIntent.current = next;
    writeTopicScope(Number(id), next);
    setScopeChoice((value) => value + 1);
  }

  useEffect(() => {
    scopeIntent.current = null;
  }, [id]);

  useEffect(() => {
    if (tab !== "topics" || !repo || !id || String(repo.id) !== id) {
      return;
    }
    const hasLanguage = Boolean(repo.language);
    if (scopeIntent.current) {
      const desired = scopeIntent.current;
      writeTopicScope(repo.id, desired);
      const shown: TopicScope = scopeQuery === "language" ? "language" : "all";
      if (shown !== desired) {
        setSearchParams(
          desired === "language" ? { tab: "topics", scope: "language" } : { tab: "topics" },
          { replace: true },
        );
      }
      return;
    }
    if (scopeQuery != null) {
      const next: TopicScope = scopeQuery === "language" && hasLanguage ? "language" : "all";
      writeTopicScope(repo.id, next);
      if (next === "all") {
        setSearchParams({ tab: "topics" }, { replace: true });
      }
      return;
    }
    const stored = readTopicScope(repo.id);
    if (stored === "language" && hasLanguage) {
      setSearchParams({ tab: "topics", scope: "language" }, { replace: true });
    } else if (stored === "language") {
      writeTopicScope(repo.id, "all");
    }
  }, [tab, repo, id, scopeQuery, setSearchParams, scopeChoice]);

  const load = useCallback(async () => {
    if (!id) {
      return;
    }
    const [repository, trafficData, searchData, plainTopicData, languageTopicData] = await Promise.all([
      api<Repository>(`/api/v1/repositories/${id}`),
      api<RepositoryTraffic>(
        `/api/v1/repositories/${id}/traffic?period=${period}&includeServicePaths=${showServicePaths}`,
      ),
      api<SearchHistory[]>(`/api/v1/repositories/${id}/search-visibility`),
      api<TopicHistory[]>(`/api/v1/repositories/${id}/topics-visibility?scope=all`),
      api<TopicHistory[]>(`/api/v1/repositories/${id}/topics-visibility?scope=language`),
    ]);
    setRepo(repository);
    setTraffic(trafficData);
    setVisibility(searchData);
    setPlainTopics(plainTopicData);
    setLanguageTopics(languageTopicData);
  }, [id, period, showServicePaths]);

  useEffect(() => {
    function onCollected() {
      void load().catch((err: Error) => setError(err.message));
    }
    window.addEventListener("repos-collected", onCollected);
    return () => window.removeEventListener("repos-collected", onCollected);
  }, [load]);

  useEffect(() => {
    if (loading || tab !== "overview" || (!focusHealth && searchParams.get("focus") !== "health")) {
      return;
    }
    document.getElementById("repository-health")?.scrollIntoView({ block: "start" });
    setFocusHealth(false);
  }, [focusHealth, tab, loading, searchParams]);

  const loadedRepositoryId = useRef<string | undefined>(undefined);
  useEffect(() => {
    let cancelled = false;
    if (loadedRepositoryId.current !== id) {
      setLoading(true);
    }
    load()
      .catch((err: Error) => {
        if (!cancelled) {
          setError(err.message);
        }
      })
      .finally(() => {
        if (!cancelled) {
          loadedRepositoryId.current = id;
          setLoading(false);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [load, id]);

  const runStatus = traffic?.lastCollection?.status;
  const runActive = runStatus === "RUNNING" || runStatus === "PLANNED";
  const searchActive = visibility.some((item) => {
    const status = item.searchStatus;
    return status === "RUNNING" || status === "READY" || status === "RETRY";
  });
  const topicsActive = knownTopics.some((item) => {
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
    if (!allowMutations || !id) {
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
    if (!allowMutations || runningQueryId != null || runningAll || searchActive) {
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
    if (!allowMutations || !id || runningQueryId != null || runningAll || searchActive || visibility.length === 0) {
      return;
    }
    setRunningAll(true);
    try {
      await api(`/api/v1/repositories/${id}/search-queries/run`, { method: "POST" });
      await load();
    } catch (err) {
      setQueryError((err as Error).message);
    } finally {
      setRunningAll(false);
    }
  }

  async function runTopicWatch(watchId: number) {
    if (!allowMutations || runningQueryId != null || runningAll || topicsActive) {
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
    if (!allowMutations || !id || runningQueryId != null || runningAll || topicsActive || topicsVisibility.length === 0) {
      return;
    }
    setRunningAll(true);
    try {
      await api(`/api/v1/repositories/${id}/topic-watches/run?scope=${scope}`, { method: "POST" });
      await load();
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setRunningAll(false);
    }
  }

  async function deleteQuery(queryId: number) {
    if (!allowMutations || !id) {
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
              className="inline-flex items-center gap-1 text-primary underline"
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
                tab === key ? "bg-card text-foreground shadow-xs" : "text-muted-foreground hover:text-foreground",
              )}
              onClick={() => setTab(key)}
            >
              {label}
            </button>
          ))}
        </div>
        {(tab === "traffic" || tab === "stats" || tab === "search" || tab === "topics" || tab === "growth-events") && (
          <div className="flex items-center gap-2">
            {tab === "topics" && repo.language && (
              <div className="inline-flex items-center rounded-lg border bg-muted p-1">
                {(["all", "language"] as const).map((key) => (
                  <button
                    key={key}
                    type="button"
                    className={cn(
                      "rounded-md px-3 py-1.5 text-sm font-medium",
                      scope === key ? "bg-card text-foreground shadow-xs" : "text-muted-foreground hover:text-foreground",
                    )}
                    onClick={() => setScope(key)}
                  >
                    {key === "all" ? "All" : repo.language}
                  </button>
                ))}
              </div>
            )}
            <PeriodSelector period={period} onPeriod={setPeriod} />
          </div>
        )}
      </div>
      {tab === "overview" && (
        <Overview repo={repo} lastCollection={traffic.lastCollection} trafficGap={traffic.trafficGap} />
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
          period={period}
          visibility={topicsVisibility}
          knownTopics={knownTopics}
          runningQueryId={runningQueryId}
          runningAll={runningAll}
          allowMutations={allowMutations}
          onRun={runTopicWatch}
          onRunAll={() => void runAllTopics()}
        />
      )}
      {tab === "search" && (
        <SearchPanel
          repositoryId={repo.id}
          period={period}
          visibility={visibility}
          queryText={queryText}
          setQueryText={(value) => {
            setQueryText(value);
            setQueryError(null);
          }}
          queryError={queryError}
          runningQueryId={runningQueryId}
          runningAll={runningAll}
          allowMutations={allowMutations}
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

function SectionTitle({ icon: Icon, children }: { icon: LucideIcon; children: string }) {
  return (
    <h2 className="mb-4 flex items-center gap-2 font-medium">
      <Icon className="size-5 shrink-0 text-slate-500" aria-hidden="true" />
      {children}
    </h2>
  );
}

function FieldLabel({ icon: Icon, children }: { icon: LucideIcon; children: string }) {
  return (
    <dt className="flex items-center gap-2 text-muted-foreground">
      <Icon className="size-4 shrink-0 text-slate-500" aria-hidden="true" />
      {children}
    </dt>
  );
}

function Overview({
  repo,
  lastCollection,
  trafficGap,
}: {
  repo: Repository;
  lastCollection?: CollectionRun;
  trafficGap?: RepositoryTraffic["trafficGap"];
}) {
  const about = repo.description?.trim();
  return (
    <div className="space-y-4">
      {about && (
        <Card>
          <SectionTitle icon={Info}>About this repository</SectionTitle>
          <p className="text-sm font-normal text-muted-foreground">{about}</p>
        </Card>
      )}
      <TopicsCard topics={repo.topics ?? []} />
      <div className="grid gap-4 md:grid-cols-2">
        <Card className="h-full">
          <SectionTitle icon={Package}>Overview</SectionTitle>
          <dl className="grid grid-cols-2 gap-x-4 gap-y-3 text-sm">
            <FieldLabel icon={UserRound}>Owner</FieldLabel>
            <dd>{repo.owner.login}</dd>
            <FieldLabel icon={LockKeyhole}>Visibility</FieldLabel>
            <dd>{repo.visibility}</dd>
            <FieldLabel icon={Star}>Stars</FieldLabel>
            <dd>{formatNumber(repo.stars)}</dd>
            <FieldLabel icon={Eye}>Watchers</FieldLabel>
            <dd>{formatNumber(repo.watchers)}</dd>
            <FieldLabel icon={GitFork}>Forks</FieldLabel>
            <dd>{formatNumber(repo.forks)}</dd>
            <FieldLabel icon={UsersRound}>Contributors</FieldLabel>
            <dd>{formatNumber(repo.contributors)}</dd>
            <FieldLabel icon={Code2}>Language</FieldLabel>
            <dd>{repo.language ?? "—"}</dd>
            <FieldLabel icon={GitBranch}>Default branch</FieldLabel>
            <dd>{repo.defaultBranch ?? "—"}</dd>
            <FieldLabel icon={Clock3}>Last commit</FieldLabel>
            <dd>{formatSyncTime(repo.lastCommitAt) ?? "—"}</dd>
            <FieldLabel icon={RefreshCw}>Last GitHub update</FieldLabel>
            <dd>{formatSyncTime(repo.githubUpdatedAt) ?? "—"}</dd>
            <FieldLabel icon={ExternalLink}>GitHub</FieldLabel>
            <dd>
              <a className="text-primary hover:underline" href={repo.githubUrl ?? `https://github.com/${repo.fullName}`} target="_blank" rel="noreferrer">
                Open repository
              </a>
            </dd>
          </dl>
        </Card>
        <CollectionStatusCard run={lastCollection} trafficGap={trafficGap} />
      </div>
      {repo.health && <RepositoryHealthSection health={repo.health} />}
    </div>
  );
}

function TopicsCard({ topics }: { topics: string[] }) {
  return (
    <Card>
      <SectionTitle icon={Tag}>Topics</SectionTitle>
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
      <div className="grid gap-8 md:grid-cols-2">
        <HealthCheckList title="Discoverability" icon={Search} items={health.discoverability} />
        <HealthCheckList title="Community Standards" icon={UsersRound} items={health.communityStandards} />
      </div>
    </Card>
  );
}

function HealthCheckList({
  title,
  icon: Icon,
  items,
}: {
  title: string;
  icon: LucideIcon;
  items: { label: string; passed: boolean }[];
}) {
  return (
    <div>
      <h3 className="mb-3 flex items-center gap-2 text-sm font-medium">
        <Icon className="size-5 shrink-0 text-slate-500" aria-hidden="true" />
        {title}
      </h3>
      <div className="space-y-2 text-sm">
        {items.map((item) => (
          <div key={item.label} className={cn("flex items-center gap-2", item.passed ? "text-emerald-700" : "text-muted-foreground")}>
            {item.passed ? (
              <CircleCheck className="size-4 shrink-0" aria-hidden="true" />
            ) : (
              <Circle className="size-4 shrink-0 text-slate-500" aria-hidden="true" />
            )}
            <span>{item.label}</span>
          </div>
        ))}
      </div>
    </div>
  );
}

function CollectionStatusCard({
  run,
  trafficGap,
}: {
  run?: CollectionRun;
  trafficGap?: RepositoryTraffic["trafficGap"];
}) {
  if (trafficGap?.reason === "COLLECTION_NOT_EXECUTED") {
    return (
      <Card className="h-full">
        <CollectionTitle />
        <p className="text-sm">⛔ {repositoryGapText(trafficGap)}</p>
      </Card>
    );
  }
  if (!run) {
    return (
      <Card className="h-full">
        <CollectionTitle />
        <h3 className="font-medium">No collection data yet.</h3>
        <p className="mt-2 text-sm text-muted-foreground">
          Run your first collection to fetch GitHub repository statistics.
        </p>
      </Card>
    );
  }

  const failed = run.jobs.find((job) => job.status === "FAILED");
  const failureReason = collectionFailureReason(failed?.errorMessage, failed?.jobType);
  return (
    <Card className="h-full">
      <CollectionTitle success={run.status === "SUCCESS"} />
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
      {failureReason && <p className="mt-2 text-sm">{failureReason}</p>}
      <div className="mt-4 divide-y divide-border text-sm">
        {run.jobs.map((job) => (
          <JobRow key={job.jobType} job={job} businessDate={run.businessDate} trafficGap={trafficGap} />
        ))}
      </div>
    </Card>
  );
}

function JobRow({
  job,
  businessDate,
  trafficGap,
}: {
  job: CollectionJob;
  businessDate: string;
  trafficGap?: RepositoryTraffic["trafficGap"];
}) {
  if (job.status === "FAILED") {
    return <div className="py-2 text-sm text-red-700">⛔ Collection failed for {formatChartAxisDate(businessDate)}.</div>;
  }
  if (job.jobType === "TRAFFIC" && trafficGap?.reason === "GITHUB_DATA_MISSING") {
    return <div className="py-2 text-sm text-amber-700">⚠ {trafficJobWarning(trafficGap)}</div>;
  }
  const success = job.status === "SUCCESS";
  return (
    <div className={cn("flex items-center gap-2 py-2", success ? "text-emerald-700" : "text-muted-foreground")}>
      {success ? (
        <Check className="size-4 shrink-0" aria-hidden="true" />
      ) : (
        <Circle className="size-4 shrink-0 text-slate-500" aria-hidden="true" />
      )}
      <span>{JOB_LABELS[job.jobType] ?? job.jobType}</span>
    </div>
  );
}

function CollectionTitle({ success = false }: { success?: boolean }) {
  return (
    <div className="mb-4 flex items-center gap-2">
      <CircleCheck className="size-5 shrink-0 text-slate-500" aria-hidden="true" />
      <h2 className="font-medium">Collection Status</h2>
      {success && <CircleCheck className="ml-auto size-8 shrink-0 text-emerald-600" aria-hidden="true" />}
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

  const loadEvents = useCallback(() => {
    api<GrowthEvent[]>(`/api/v1/repositories/${repositoryId}/growth-events?period=${period}`)
      .then((rows) => setEvents(asGrowthEvents(rows)))
      .catch(() => setEvents([]));
  }, [repositoryId, period]);

  useEffect(() => {
    loadEvents();
  }, [loadEvents]);

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
      <ChartCard title="Traffic" events={events} repositoryId={repositoryId} period={period} context={`repository:${repositoryId}:traffic`} onEventsChanged={loadEvents}>
        <ChartModePlot
          chartId={repoTrafficChartId(repositoryId)}
          series={REPO_TRAFFIC_SERIES}
          style={{ height: 360, width: "100%" }}
          build={({ events: marked }) => trafficChartOption(traffic.traffic, marked, period, traffic.trafficGap)}
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
  period,
  visibility,
  knownTopics,
  runningQueryId,
  runningAll,
  allowMutations = true,
  onRun,
  onRunAll,
}: {
  repositoryId: number;
  language?: string;
  scope: TopicScope;
  period: Period;
  visibility: TopicHistory[];
  knownTopics: TopicHistory[];
  runningQueryId: number | null;
  runningAll: boolean;
  allowMutations?: boolean;
  onRun: (id: number) => void;
  onRunAll: () => void;
}) {
  const [hoveredWatchId, setHoveredWatchId] = useState<number | null>(null);
  const [selectedSlugs, setSelectedSlugs] = useState<string[]>([]);
  const knownKey = knownTopics.map((item) => `${item.watch.topic}:${item.currentRank ?? ""}`).join("\n");
  const knownSamples = useMemo(
    () => knownTopics.map((item) => ({ slug: item.watch.topic, rank: item.currentRank })),
    [knownKey],
  );
  useEffect(() => {
    if (knownSamples.length === 0) {
      return;
    }
    setSelectedSlugs(resolveTopicSelection(repositoryId, knownSamples));
  }, [repositoryId, knownSamples]);
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
  const scopeQuery = scope === "language" ? "?scope=language" : "";
  const selected = new Set(selectedSlugs);
  const visibleSlugs = visibility.map((item) => item.watch.topic);
  const allChecked = visibleSlugs.length > 0 && visibleSlugs.every((slug) => selected.has(slug));

  function writeSelection(slugs: string[]) {
    setSelectedSlugs(slugs);
    writeTopicSelection(repositoryId, slugs);
  }

  return (
    <div className="space-y-4">
      <TopicVisibilityKpis topics={visibility} period={period} />
      <CombinedTopicRankChart
        repositoryId={repositoryId}
        scope={scope}
        period={period}
        visibility={visibility}
        selectedSlugs={selectedSlugs}
        highlightWatchId={hoveredWatchId}
      />
      <Card>
        <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
          <h2 className="font-medium">Topics</h2>
          {allowMutations && (
            <Button
              className="bg-muted text-foreground"
              disabled={visibility.length === 0 || searchBusy}
              onClick={onRunAll}
            >
              {searchBusy ? "Running..." : "Run all"}
            </Button>
          )}
        </div>
        {visibility.length === 0 ? (
          <p className="text-sm text-muted-foreground">
            {language && scope === "language"
              ? "No language-scoped topic watches yet."
              : "No topics yet. Add topics to the repository on GitHub."}
          </p>
        ) : (
          <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="text-left text-muted-foreground">
                <th className="py-2 pr-3">
                  <input
                    ref={(node) => {
                      if (node) {
                        node.indeterminate = selectedSlugs.length > 0 && visibleSlugs.some((slug) => selected.has(slug)) && !allChecked;
                      }
                    }}
                    type="checkbox"
                    aria-label="Chart lines"
                    checked={allChecked}
                    onChange={() => {
                      const hidden = selectedSlugs.filter((slug) => !visibleSlugs.includes(slug));
                      writeSelection(allChecked ? hidden : [...hidden, ...visibleSlugs]);
                    }}
                  />
                </th>
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
                    <td className="py-3 pr-3">
                      <input
                        type="checkbox"
                        aria-label={`Chart line for ${item.watch.topic}`}
                        checked={selected.has(item.watch.topic)}
                        onChange={(event) => {
                          const next = event.target.checked
                            ? [...selectedSlugs, item.watch.topic]
                            : selectedSlugs.filter((slug) => slug !== item.watch.topic);
                          writeSelection(next);
                        }}
                      />
                    </td>
                    <td className="py-3 pr-4">
                      <span className="inline-flex items-center gap-2">
                        {selected.has(item.watch.topic) && (
                          <span
                            className="inline-block h-2.5 w-2.5 shrink-0 rounded-full"
                            style={{ backgroundColor: rankLineColor(visibility.findIndex((row) => row.watch.id === item.watch.id)) }}
                          />
                        )}
                        <Link
                          className="text-primary hover:underline"
                          to={`/repositories/${repositoryId}/topics/${encodeURIComponent(item.watch.topic)}${scopeQuery}`}
                        >
                          {item.watch.topic}
                        </Link>
                      </span>
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
                      {allowMutations && (
                        <div className="flex justify-end">
                          <Button
                            className="bg-muted text-foreground"
                            disabled={running || searchBusy}
                            onClick={() => onRun(item.watch.id)}
                          >
                            {running || runningAll ? "Running..." : "Run"}
                          </Button>
                        </div>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
          </div>
        )}
      </Card>
    </div>
  );
}

function CombinedTopicRankChart({
  repositoryId,
  scope,
  period,
  visibility,
  selectedSlugs,
  highlightWatchId,
}: {
  repositoryId: number;
  scope: TopicScope;
  period: Period;
  visibility: TopicHistory[];
  selectedSlugs: string[];
  highlightWatchId: number | null;
}) {
  const [events, setEvents] = useState<GrowthEvent[]>([]);

  const loadEvents = useCallback(() => {
    api<GrowthEvent[]>(`/api/v1/repositories/${repositoryId}/growth-events?period=${period}`)
      .then((rows) => setEvents(asGrowthEvents(rows)))
      .catch(() => setEvents([]));
  }, [repositoryId, period]);

  useEffect(() => {
    loadEvents();
  }, [loadEvents]);

  const drawn = useMemo(
    () => visibility.filter((item) => selectedSlugs.includes(item.watch.topic)),
    [visibility, selectedSlugs],
  );

  const dates = useMemo(
    () => datesFromHistory(drawn.map((item) => item.points), drawn.flatMap((item) => item.missedDates ?? [])),
    [drawn],
  );
  const rankSeries = useMemo(
    () => drawn.map((item) => ({
      name: item.watch.topic,
      points: item.points,
      limit: item.watch.resultLimit,
      missedDates: item.missedDates,
      highlighted: highlightWatchId === item.watch.id,
      dimmed: highlightWatchId != null && highlightWatchId !== item.watch.id,
      color: rankLineColor(visibility.findIndex((row) => row.watch.id === item.watch.id)),
    })),
    [drawn, highlightWatchId, visibility],
  );
  const series = useMemo(
    () => drawn.map((item) => ({ key: String(item.watch.id), name: item.watch.topic })),
    [drawn],
  );

  if (drawn.length === 0) {
    return (
      <ChartCard title="Rank History" context={`repository:${repositoryId}:topics`}>
        <p className="text-sm text-muted-foreground">Select topics from the table to display rank history</p>
      </ChartCard>
    );
  }
  return (
    <ChartCard title="Rank History" events={events} repositoryId={repositoryId} period={period} context={`repository:${repositoryId}:topics`} onEventsChanged={loadEvents}>
      <ChartModePlot
        chartId={repoTopicsChartId(repositoryId, scope)}
        series={series}
        persistSelection={false}
        style={{ height: 360, width: "100%" }}
        build={({ axis, events: marked }) => rankHistoryOption({
          dates,
          legend: true,
          legendToggle: false,
          period,
          axis,
          events: marked,
          series: rankSeries,
        })}
      />
    </ChartCard>
  );
}

function SearchPanel({
  repositoryId,
  period,
  visibility,
  queryText,
  setQueryText,
  queryError,
  runningQueryId,
  runningAll,
  allowMutations = true,
  onCreate,
  onRun,
  onRunAll,
  onDelete,
}: {
  repositoryId: number;
  period: Period;
  visibility: SearchHistory[];
  queryText: string;
  setQueryText: (value: string) => void;
  queryError: string | null;
  runningQueryId: number | null;
  runningAll: boolean;
  allowMutations?: boolean;
  onCreate: () => void;
  onRun: (id: number) => void;
  onRunAll: () => void;
  onDelete: (id: number) => void;
}) {
  const [hoveredQueryId, setHoveredQueryId] = useState<number | null>(null);
  const [selectedIds, setSelectedIds] = useState<number[]>([]);
  useEffect(() => {
    setSelectedIds(resolveSearchQuerySelection(
      repositoryId,
      visibility.map((item) => ({ id: item.query.id, rank: item.currentRank })),
    ));
  }, [repositoryId, visibility]);
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

  const selected = new Set(selectedIds);
  const allChecked = visibility.length > 0 && visibility.every((item) => selected.has(item.query.id));

  function writeSelection(ids: number[]) {
    setSelectedIds(ids);
    writeSearchQuerySelection(repositoryId, ids);
  }

  return (
    <div className="space-y-4">
      <SearchVisibilityKpis queries={visibility} period={period} />
      <CombinedRankChart
        repositoryId={repositoryId}
        period={period}
        visibility={visibility}
        highlightQueryId={hoveredQueryId}
        selectedIds={selectedIds}
      />
      <Card>
        {allowMutations ? (
          <>
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
          </>
        ) : (
          <h2 className="mb-3 font-medium">Search Queries</h2>
        )}
        <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="text-left text-muted-foreground">
              <th className="py-2 pr-3">
                <input
                  ref={(node) => {
                    if (node) {
                      node.indeterminate = selectedIds.length > 0 && !allChecked;
                    }
                  }}
                  type="checkbox"
                  aria-label="Chart lines"
                  checked={allChecked}
                  onChange={() => writeSelection(allChecked ? [] : visibility.map((item) => item.query.id))}
                />
              </th>
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
                  <td className="py-3 pr-3">
                    <input
                      type="checkbox"
                      aria-label={`Chart line for ${item.query.name}`}
                      checked={selected.has(item.query.id)}
                      onChange={(event) => {
                        const next = event.target.checked
                          ? [...selectedIds, item.query.id]
                          : selectedIds.filter((id) => id !== item.query.id);
                        writeSelection(next);
                      }}
                    />
                  </td>
                  <td className="py-3 pr-4">
                    <span className="inline-flex items-center gap-2">
                      {selected.has(item.query.id) && (
                        <span
                          className="inline-block h-2.5 w-2.5 shrink-0 rounded-full"
                          style={{ backgroundColor: rankLineColor(visibility.findIndex((row) => row.query.id === item.query.id)) }}
                        />
                      )}
                      <Link
                        className="text-primary hover:underline"
                        to={`/repositories/${repositoryId}/search-queries/${item.query.id}`}
                      >
                        {item.query.name}
                      </Link>
                    </span>
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
                    {allowMutations && (
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
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
        </div>
      </Card>
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
  period,
  visibility,
  highlightQueryId,
  selectedIds,
}: {
  repositoryId: number;
  period: Period;
  visibility: SearchHistory[];
  highlightQueryId: number | null;
  selectedIds: number[];
}) {
  const [events, setEvents] = useState<GrowthEvent[]>([]);

  const loadEvents = useCallback(() => {
    api<GrowthEvent[]>(`/api/v1/repositories/${repositoryId}/growth-events?period=${period}`)
      .then((rows) => setEvents(asGrowthEvents(rows)))
      .catch(() => setEvents([]));
  }, [repositoryId, period]);

  useEffect(() => {
    loadEvents();
  }, [loadEvents]);

  const drawn = useMemo(
    () => visibility.filter((item) => selectedIds.includes(item.query.id)),
    [visibility, selectedIds],
  );
  const dates = useMemo(
    () => datesFromHistory(drawn.map((item) => item.points), drawn.flatMap((item) => item.missedDates)),
    [drawn],
  );
  const rankSeries = useMemo(
    () => drawn.map((item) => ({
      name: item.query.name,
      points: item.points,
      limit: item.query.resultLimit,
      missedDates: item.missedDates,
      highlighted: highlightQueryId === item.query.id,
      dimmed: highlightQueryId != null && highlightQueryId !== item.query.id,
      color: rankLineColor(visibility.findIndex((row) => row.query.id === item.query.id)),
    })),
    [drawn, highlightQueryId, visibility],
  );
  const series = useMemo(
    () => drawn.map((item) => ({ key: String(item.query.id), name: item.query.name })),
    [drawn],
  );

  if (drawn.length === 0) {
    return (
      <ChartCard title="Rank History" context={`repository:${repositoryId}:search`}>
        <p className="text-sm text-muted-foreground">Select search queries from the table to display rank history</p>
      </ChartCard>
    );
  }
  return (
    <ChartCard title="Rank History" events={events} repositoryId={repositoryId} period={period} context={`repository:${repositoryId}:search`} onEventsChanged={loadEvents}>
      <ChartModePlot
        chartId={repoSearchChartId(repositoryId)}
        series={series}
        persistSelection={false}
        style={{ height: 360, width: "100%" }}
        build={({ axis, events: marked }) => rankHistoryOption({
          dates,
          legend: true,
          legendToggle: false,
          period,
          axis,
          events: marked,
          series: rankSeries,
        })}
      />
    </ChartCard>
  );
}

function formatBusinessDate(value: string) {
  return new Intl.DateTimeFormat("en-US", { month: "short", day: "numeric", year: "numeric" }).format(
    new Date(`${value}T00:00:00`),
  );
}
