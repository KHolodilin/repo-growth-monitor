import { useEffect, useMemo, useState } from "react";
import { useParams } from "react-router-dom";
import { api, type Repository, type SearchHistory, type SearchRunResults, type SearchSnapshotSeries } from "../lib/api";
import { formatChartAxisDate, formatDelta, formatNumber, formatRank, formatSyncTime, cn } from "../lib/utils";
import { recentMissedDates } from "../lib/rankChart";
import { queryDetailsChart, searchSeriesFromPayload, type SearchChartMetric } from "../lib/searchSeriesChart";
import { ChartCard, useChartMode } from "../components/ChartDownloadMenu";
import { PersistentECharts } from "../components/PersistentECharts";
import { Button, Card, Skeleton } from "../components/ui";
import { PageBreadcrumb } from "../components/PageBreadcrumb";
import { SearchResultsTable } from "../components/SearchResultsTable";

export function QueryDetailsPage() {
  const { repositoryId, queryId } = useParams();
  const [repo, setRepo] = useState<Repository | null>(null);
  const [history, setHistory] = useState<SearchHistory | null>(null);
  const [results, setResults] = useState<SearchRunResults | null>(null);
  const [series, setSeries] = useState<SearchSnapshotSeries[]>([]);
  const [selected, setSelected] = useState<number[]>([]);
  const [metric, setMetric] = useState<SearchChartMetric>("place");
  const [error, setError] = useState<string | null>(null);
  const [running, setRunning] = useState(false);

  async function load() {
    if (!repositoryId || !queryId) {
      return;
    }
    const [repository, queryHistory] = await Promise.all([
      api<Repository>(`/api/v1/repositories/${repositoryId}`),
      api<SearchHistory>(`/api/v1/search-queries/${queryId}/history`),
    ]);
    setRepo(repository);
    setHistory(queryHistory);
    try {
      setResults(await api<SearchRunResults>(`/api/v1/search-queries/${queryId}/results`));
    } catch {
      setResults(null);
    }
    try {
      setSeries(searchSeriesFromPayload(await api(`/api/v1/search-queries/${queryId}/series`)));
    } catch {
      setSeries([]);
    }
  }

  useEffect(() => {
    load().catch((err: Error) => setError(err.message));
  }, [repositoryId, queryId]);

  const searchBusy =
    history?.searchStatus === "RUNNING"
    || history?.searchStatus === "READY"
    || history?.searchStatus === "RETRY";

  useEffect(() => {
    if (!searchBusy) {
      setRunning(false);
      return;
    }
    const timer = window.setInterval(() => {
      void load().catch(() => undefined);
    }, 2500);
    return () => window.clearInterval(timer);
  }, [searchBusy, repositoryId, queryId]);

  async function runNow() {
    if (!queryId || running || searchBusy) {
      return;
    }
    setRunning(true);
    try {
      await api(`/api/v1/search-queries/${queryId}/run`, { method: "POST" });
      await load();
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setRunning(false);
    }
  }

  if (error) {
    return <p className="text-red-600">{error}</p>;
  }
  if (!repo || !history) {
    return (
      <div className="space-y-4">
        <Skeleton className="h-7 w-80" />
        <Skeleton className="h-24" />
      </div>
    );
  }

  const busy = running || searchBusy;
  const recentMissed = recentMissedDates(history.missedDates);

  return (
    <div className="space-y-6">
      <header className="grid grid-cols-1 items-start gap-x-4 gap-y-2 sm:grid-cols-[minmax(0,1fr)_auto]">
        <div className="sm:col-start-1 sm:row-start-1">
          <PageBreadcrumb
            items={[
              { label: "Portfolio", to: "/dashboard" },
              {
                label: repo.fullName,
                repoSwitcher: {
                  currentId: repo.id,
                  hrefFor: (id) => `/repositories/${id}?tab=search`,
                },
              },
              { label: "Search Visibility", to: `/repositories/${repo.id}?tab=search` },
            ]}
          />
          <h1 className="mt-2 break-all text-xl font-semibold">
            <a
              className="text-primary hover:underline"
              href={`https://github.com/search?q=${encodeURIComponent(history.query.query)}&type=repositories`}
              target="_blank"
              rel="noreferrer"
            >
              {history.query.query}
            </a>
          </h1>
          <p className="mt-1 text-sm text-muted-foreground">
            Last checked: {formatSyncTime(history.lastChecked) ?? "—"}
            {history.searchStatus ? ` · Search: ${history.searchStatus}` : ""}
            {history.enrichmentStatus ? ` · Enrichment: ${history.enrichmentStatus}` : ""}
          </p>
        </div>
        <Button className="justify-self-start sm:col-start-2 sm:row-start-1 sm:justify-self-end" disabled={busy} onClick={() => void runNow()}>
          {busy ? "Running..." : "Run now"}
        </Button>
      </header>

      <div className="grid grid-cols-2 gap-4 lg:grid-cols-5">
        <Kpi label="Current Rank" value={formatRank(history.currentRank, history.query.resultLimit)} />
        <Kpi label="7d Change" value={formatDelta(history.change7d)} />
        <Kpi label="30d Change" value={formatDelta(history.change30d)} />
        <Kpi label="Best Rank" value={formatRank(history.bestRank, history.query.resultLimit)} />
        <Kpi label="Total Results" value={formatNumber(history.totalResults)} />
      </div>

      <ChartCard
        title="Rank History"
        repositoryId={repo.id}
        period="all"
        controls={
          <div className="inline-flex rounded-lg border bg-muted p-1" role="group" aria-label="Chart metric">
            {([
              ["place", "Place"],
              ["stars", "Stars"],
              ["forks", "Forks"],
            ] as const).map(([key, label]) => (
              <button
                key={key}
                type="button"
                className={cn(
                  "rounded-md px-3 py-1.5 text-sm font-medium",
                  metric === key ? "bg-card text-foreground shadow-xs" : "text-muted-foreground hover:text-foreground",
                )}
                aria-pressed={metric === key}
                onClick={() => setMetric(key)}
              >
                {label}
              </button>
            ))}
          </div>
        }
      >
        {recentMissed.length > 0 && (
          <p className="mb-3 rounded-md border border-amber-300 bg-amber-50 px-3 py-2 text-sm text-amber-900">
            Not collected on {recentMissed.map(formatChartAxisDate).join(", ")}. GitHub Search only returns today's
            ranking, so these days stay empty.
          </p>
        )}
        <QueryRankChart
          history={history}
          repo={repo}
          rows={results?.rows ?? null}
          series={series}
          selected={selected}
          metric={metric}
        />
      </ChartCard>

      <Card>
        <h2 className="mb-3 font-medium">Current Search Results</h2>
        {!results ? (
          <p className="text-sm text-muted-foreground">No search snapshot yet.</p>
        ) : (
          <SearchResultsTable
            rows={results.rows}
            repositoryId={repo.id}
            trackedGithubId={repo.githubId}
            selectedIds={selected}
            onToggle={(id) =>
              setSelected((current) => (current.includes(id) ? current.filter((item) => item !== id) : [...current, id]))
            }
            nameTo={(row) =>
              `/repositories/${repo.id}/search-queries/${history.query.id}/competitors/${row.result.githubRepositoryId}`
            }
          />
        )}
      </Card>
    </div>
  );
}

function QueryRankChart({
  history,
  repo,
  rows,
  series,
  selected,
  metric,
}: {
  history: SearchHistory;
  repo: Repository;
  rows: SearchRunResults["rows"] | null;
  series: SearchSnapshotSeries[];
  selected: number[];
  metric: SearchChartMetric;
}) {
  const { axis, events } = useChartMode();
  const chart = useMemo(
    () => queryDetailsChart({ history, repo, rows, series, selected, metric, axis, events }),
    [history, repo, rows, series, selected, metric, axis, events],
  );
  if (!chart) {
    return null;
  }
  return (
    <PersistentECharts
      chartId={`${repo.id}:query:${history.query.id}`}
      series={chart.series}
      option={chart.option}
      style={{ height: 360, width: "100%" }}
    />
  );
}

function Kpi({ label, value }: { label: string; value: string }) {
  return (
    <Card>
      <div className="text-sm text-muted-foreground">{label}</div>
      <div className="mt-2 text-2xl font-semibold">{value}</div>
    </Card>
  );
}
