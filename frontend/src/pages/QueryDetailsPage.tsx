import { useEffect, useMemo, useState } from "react";
import { useParams } from "react-router-dom";
import ReactECharts from "echarts-for-react";
import { api, type Repository, type SearchHistory, type SearchRunResults } from "../lib/api";
import { formatDelta, formatNumber, formatRank, formatSyncTime } from "../lib/utils";
import { datesFromHistory, rankHistoryOption } from "../lib/rankChart";
import { Button, Card, Skeleton } from "../components/ui";
import { PageBreadcrumb } from "../components/PageBreadcrumb";
import { SearchResultsTable } from "../components/SearchResultsTable";

export function QueryDetailsPage() {
  const { repositoryId, queryId } = useParams();
  const [repo, setRepo] = useState<Repository | null>(null);
  const [history, setHistory] = useState<SearchHistory | null>(null);
  const [results, setResults] = useState<SearchRunResults | null>(null);
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

  const option = useMemo(() => {
    if (!history) {
      return null;
    }
    const dates = datesFromHistory([history.points]);
    return rankHistoryOption({
      dates,
      series: [
        {
          name: history.query.name,
          points: history.points,
          limit: history.query.resultLimit,
        },
      ],
    });
  }, [history]);

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
          <h1 className="mt-2 break-all text-xl font-semibold">{history.query.query}</h1>
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

      {option && (
        <Card>
          <h2 className="mb-3 font-medium">Rank History</h2>
          <ReactECharts option={option} style={{ height: 360, width: "100%" }} />
        </Card>
      )}

      <Card>
        <h2 className="mb-3 font-medium">Current Search Results</h2>
        {!results ? (
          <p className="text-sm text-muted-foreground">No search snapshot yet.</p>
        ) : (
          <SearchResultsTable
            rows={results.rows}
            trackedGithubId={repo.githubId}
          />
        )}
      </Card>
    </div>
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
