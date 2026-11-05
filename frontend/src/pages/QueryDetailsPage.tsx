import { PeriodSelector, usePeriod } from "../components/PeriodSelector";
import { RankSummary } from "../components/RankSummary";
import { useEffect, useMemo, useState } from "react";
import { useParams, useSearchParams } from "react-router-dom";
import { api, type Repository, type SearchHistory, type SearchRunResults, type SearchSnapshotSeries } from "../lib/api";
import { useMutationAccess } from "../lib/mutationAccess";
import { formatSyncTime, cn } from "../lib/utils";
import { SEARCH_METRICS, queryDetailsChart, searchSeriesFromPayload, type SearchChartMetric } from "../lib/searchSeriesChart";
import { ComparisonSeries } from "../components/ComparisonSeries";
import { ChartCard, useChartMode } from "../components/ChartDownloadMenu";
import { PersistentECharts } from "../components/PersistentECharts";
import { Button, Card, Skeleton } from "../components/ui";
import { PageBreadcrumb } from "../components/PageBreadcrumb";
import { SearchResultsTable } from "../components/SearchResultsTable";

export function QueryDetailsPage() {
  const { repositoryId, queryId } = useParams();
  const [period, setPeriod] = usePeriod(repositoryId);
  const { allowMutations } = useMutationAccess();
  const [repo, setRepo] = useState<Repository | null>(null);
  const [history, setHistory] = useState<SearchHistory | null>(null);
  const [results, setResults] = useState<SearchRunResults | null>(null);
  const [series, setSeries] = useState<SearchSnapshotSeries[]>([]);
  const [selected, setSelected] = useState<number[]>([]);
  const [searchParams, setSearchParams] = useSearchParams();
  const requestedMetric = searchParams.get("metric");
  const metric: SearchChartMetric = requestedMetric === "stars" || requestedMetric === "forks" ? requestedMetric : "place";
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
    setSelected([]);
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
    if (!allowMutations || !queryId || running || searchBusy) {
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

  const chartSeries = useMemo(() => {
    if (!repo || !history) {
      return [] as { key: string; name: string }[];
    }
    return queryDetailsChart({
      history,
      repo,
      rows: results?.rows ?? null,
      series,
      selected,
      metric,
    })!.series;
  }, [history, repo, results, series, selected, metric]);

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
  const metricControls = (
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
          onClick={() => setSearchParams(previous => { const next = new URLSearchParams(previous); next.set("metric", key); return next; }, { replace: true })}
        >
          {label}
        </button>
      ))}
    </div>
  );

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
        {allowMutations && (
          <Button
            className="justify-self-start sm:col-start-2 sm:row-start-1 sm:justify-self-end"
            disabled={busy}
            onClick={() => void runNow()}
          >
            {busy ? "Running..." : "Run now"}
          </Button>
        )}
      </header>

      <div role="group" aria-label="History period" className="flex justify-end"><PeriodSelector fitMobile period={period} onPeriod={setPeriod} /></div>

      <RankSummary current={history.currentRank} best={history.bestRank} limit={history.query.resultLimit} change7d={history.change7d} change30d={history.change30d} total={history.totalResults} />

      <ChartCard
        title={SEARCH_METRICS[metric].title}
        repositoryId={repo.id}
        period={period}
        context={`repository:${repo.id}:query:${history.query.id}`}
        controls={metricControls}
        mobileControls={metricControls}
        mobileSeries={[]}
      >
        {selected.some(id => !series.some(item => item.githubRepositoryId === id && item.points.length > 0)) && <p role="status" className="mb-2 text-sm text-muted-foreground">No history for one or more selected competitors.</p>}
        <ComparisonSeries series={chartSeries} />
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
  const { axis, events, period } = useChartMode();
  const chart = useMemo(
    () => queryDetailsChart({ history, repo, rows, series, selected, metric, axis, events, period })!,
    [history, repo, rows, series, selected, metric, axis, events, period],
  );
  return (
    <PersistentECharts
      chartId={`${repo.id}:query:${history.query.id}`}
      series={chart.series}
      persistSelection={false}
      exportTitle={SEARCH_METRICS[metric].title}
      option={{ ...chart.option, legend: { ...chart.option.legend, selectedMode: false } }}
      style={{ height: 360, width: "100%" }}
    />
  );
}
