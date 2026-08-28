import { useEffect, useMemo, useState } from "react";
import { Link, useParams } from "react-router-dom";
import ReactECharts from "echarts-for-react";
import { api, type Repository, type SearchHistory, type SearchRunResults } from "../lib/api";
import {
  calendarDates,
  formatActivity,
  formatDelta,
  formatNumber,
  formatPositionDelta,
  formatRank,
  formatRelativeTime,
  formatSyncTime,
} from "../lib/utils";
import { Button, Card, Skeleton } from "../components/ui";

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
    const limit = history.query.resultLimit;
    const dates = calendarDates(history.points.map((point) => point.date));
    const rankLabel = (value: number | null | undefined) => {
      if (value == null) {
        return "no data";
      }
      return value > limit ? `>${limit}` : `#${value}`;
    };
    return {
      tooltip: {
        trigger: "axis",
        formatter: (params: { marker: string; seriesName: string; value: number | null }[]) =>
          params.map((item) => `${item.marker}${rankLabel(item.value)}`).join("<br/>"),
      },
      grid: { left: 24, right: 16, top: 24, bottom: 24, containLabel: true },
      xAxis: { type: "category", data: dates, boundaryGap: false },
      yAxis: {
        type: "value",
        inverse: true,
        min: 1,
        max: limit + 1,
        axisLabel: { formatter: (value: number) => (value > limit ? `>${limit}` : `#${value}`) },
      },
      series: [
        {
          type: "line",
          connectNulls: false,
          showSymbol: true,
          data: dates.map((date) => {
            const point = history.points.find((entry) => entry.date === date);
            if (!point) {
              return null;
            }
            return point.position ?? limit + 1;
          }),
        },
      ],
    };
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
          <nav className="text-sm">
            <Link className="font-medium text-muted-foreground hover:text-foreground" to="/dashboard">
              Portfolio
            </Link>
            <span className="mx-2 text-muted-foreground" aria-hidden="true">
              ›
            </span>
            <Link className="font-medium text-muted-foreground hover:text-foreground" to={`/repositories/${repo.id}`}>
              {repo.fullName}
            </Link>
            <span className="mx-2 text-muted-foreground" aria-hidden="true">
              ›
            </span>
            <Link className="font-medium text-muted-foreground hover:text-foreground" to={`/repositories/${repo.id}?tab=search`}>
              Search Visibility
            </Link>
          </nav>
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
        <Kpi label="Last Checked" value={formatSyncTime(history.lastChecked) ?? "—"} />
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
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-left text-muted-foreground">
                  <th className="py-2">#</th>
                  <th>Repository</th>
                  <th className="text-right">Stars</th>
                  <th className="text-right">Watchers</th>
                  <th className="text-right">Forks</th>
                  <th className="text-right">Contributors</th>
                  <th>Last Activity</th>
                  <th>Activity</th>
                  <th className="text-right">Δ</th>
                </tr>
              </thead>
              <tbody>
                {results.rows.map((row) => {
                  const mine = row.result.githubRepositoryId === repo.githubId;
                  return (
                    <tr
                      key={row.result.githubRepositoryId}
                      className={`border-t ${mine ? "bg-blue-50 font-medium" : ""}`}
                      title={row.result.metadataUpdatedAt ? `Repository metadata updated: ${formatSyncTime(row.result.metadataUpdatedAt)}` : undefined}
                    >
                      <td className="py-2">{row.result.position}</td>
                      <td>
                        {mine ? (
                          <Link className="text-primary hover:underline" to={`/repositories/${repo.id}`}>
                            {row.result.fullName}
                          </Link>
                        ) : (
                          <a
                            className="text-primary hover:underline"
                            href={row.result.htmlUrl ?? `https://github.com/${row.result.fullName}`}
                            target="_blank"
                            rel="noreferrer"
                          >
                            {row.result.fullName}
                          </a>
                        )}
                      </td>
                      <td className="text-right">{formatNumber(row.result.stars)}</td>
                      <td className="text-right">{formatNumber(row.result.watchers)}</td>
                      <td className="text-right">{formatNumber(row.result.forks)}</td>
                      <td className="text-right">{formatNumber(row.result.contributors)}</td>
                      <td>{formatRelativeTime(row.result.activityAt)}</td>
                      <td>{formatActivity(row.result.activityStatus)}</td>
                      <td className="text-right">{formatPositionDelta(row.positionDelta)}</td>
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

function Kpi({ label, value }: { label: string; value: string }) {
  return (
    <Card>
      <div className="text-sm text-muted-foreground">{label}</div>
      <div className="mt-2 text-2xl font-semibold">{value}</div>
    </Card>
  );
}
