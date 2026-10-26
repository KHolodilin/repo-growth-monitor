import { useEffect, useState } from "react";
import { useParams, useSearchParams } from "react-router-dom";
import { api, type Repository, type TopicHistory, type TopicRunResults } from "../lib/api";
import { formatChartAxisDate, formatDelta, formatNumber, formatRank, formatSyncTime } from "../lib/utils";
import { datesFromHistory, rankHistoryOption, recentMissedDates } from "../lib/rankChart";
import { writeTopicScope } from "../lib/topicScope";
import { ChartCard } from "../components/ChartDownloadMenu";
import { ChartModePlot } from "../components/PersistentECharts";
import { Button, Card, Skeleton } from "../components/ui";
import { PageBreadcrumb } from "../components/PageBreadcrumb";
import { TopicResultsTable } from "../components/TopicResultsTable";
import { topicCompetitorPath } from "./TopicCompetitorCardPage";

function parseScope(value: string | null): "all" | "language" {
  return value === "language" ? "language" : "all";
}

export function TopicDetailsPage() {
  const { repositoryId, topic } = useParams();
  const [searchParams, setSearchParams] = useSearchParams();
  const scope = parseScope(searchParams.get("scope"));
  const [repo, setRepo] = useState<Repository | null>(null);
  const [history, setHistory] = useState<TopicHistory | null>(null);
  const [results, setResults] = useState<TopicRunResults | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [running, setRunning] = useState(false);

  const topicName = topic ? decodeURIComponent(topic) : "";

  async function load() {
    if (!repositoryId || !topicName) {
      return;
    }
    const [repository, topicHistory] = await Promise.all([
      api<Repository>(`/api/v1/repositories/${repositoryId}`),
      api<TopicHistory>(
        `/api/v1/repositories/${repositoryId}/topics/${encodeURIComponent(topicName)}/history?scope=${scope}`,
      ),
    ]);
    setRepo(repository);
    setHistory(topicHistory);
    try {
      setResults(
        await api<TopicRunResults>(
          `/api/v1/repositories/${repositoryId}/topics/${encodeURIComponent(topicName)}/results?scope=${scope}`,
        ),
      );
    } catch {
      setResults(null);
    }
  }

  useEffect(() => {
    load().catch((err: Error) => setError(err.message));
  }, [repositoryId, topicName, scope]);

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
  }, [searchBusy, repositoryId, topicName, scope]);

  async function runNow() {
    if (!history || running || searchBusy) {
      return;
    }
    setRunning(true);
    try {
      await api(`/api/v1/topic-watches/${history.watch.id}/run`, { method: "POST" });
      await load();
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setRunning(false);
    }
  }

  function setScope(next: "all" | "language") {
    writeTopicScope(Number(repositoryId), next);
    setSearchParams(next === "all" ? {} : { scope: next }, { replace: true });
  }

  useEffect(() => {
    if (!repositoryId || !repo || String(repo.id) !== repositoryId) {
      return;
    }
    const raw = searchParams.get("scope");
    if (raw == null) {
      return;
    }
    if (raw === "language" && repo.language) {
      writeTopicScope(repo.id, "language");
      return;
    }
    writeTopicScope(repo.id, "all");
    if (raw === "language") {
      setSearchParams({}, { replace: true });
    }
  }, [repositoryId, repo, searchParams, setSearchParams]);

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
  const languageLabel = repo.language || "Language";
  const topicUrl =
    scope === "language" && repo.language
      ? `https://github.com/topics/${encodeURIComponent(history.watch.topic)}?o=desc&s=stars&l=${encodeURIComponent(repo.language)}`
      : `https://github.com/topics/${encodeURIComponent(history.watch.topic)}?o=desc&s=stars`;

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
                  hrefFor: (id) => `/repositories/${id}?tab=topics`,
                },
              },
              { label: "Topics Visibility", to: `/repositories/${repo.id}?tab=topics${scope === "language" ? "&scope=language" : ""}` },
            ]}
          />
          <h1 className="mt-2 break-all text-xl font-semibold">
            <a className="text-primary hover:underline" href={topicUrl} target="_blank" rel="noreferrer">
              {history.watch.topic}
            </a>
          </h1>
          <p className="mt-1 text-sm text-muted-foreground">
            Last checked: {formatSyncTime(history.lastChecked) ?? "—"}
            {history.searchStatus ? ` · Search: ${history.searchStatus}` : ""}
          </p>
        </div>
        <Button className="justify-self-start sm:col-start-2 sm:row-start-1 sm:justify-self-end" disabled={busy} onClick={() => void runNow()}>
          {busy ? "Running..." : "Run now"}
        </Button>
      </header>

      {repo.language && (
        <div className="inline-flex rounded-lg border bg-muted p-1">
          {(["all", "language"] as const).map((key) => (
            <button
              key={key}
              type="button"
              className={`rounded-md px-3 py-1.5 text-sm font-medium ${
                scope === key ? "bg-card text-foreground shadow-xs" : "text-muted-foreground hover:text-foreground"
              }`}
              onClick={() => setScope(key)}
            >
              {key === "all" ? "All" : languageLabel}
            </button>
          ))}
        </div>
      )}

      <div className="grid grid-cols-2 gap-4 lg:grid-cols-5">
        <Kpi label="Current Rank" value={formatRank(history.currentRank, history.watch.resultLimit)} />
        <Kpi label="7d Change" value={formatDelta(history.change7d)} />
        <Kpi label="30d Change" value={formatDelta(history.change30d)} />
        <Kpi label="Best Rank" value={formatRank(history.bestRank, history.watch.resultLimit)} />
        <Kpi label="Total Results" value={formatNumber(history.totalResults)} />
      </div>

      <ChartCard title="Rank History" repositoryId={repo.id} period="all">
        {recentMissed.length > 0 && (
          <p className="mb-3 rounded-md border border-amber-300 bg-amber-50 px-3 py-2 text-sm text-amber-900">
            Not collected on {recentMissed.map(formatChartAxisDate).join(", ")}. GitHub Search only returns today's
            ranking, so these days stay empty.
          </p>
        )}
        <ChartModePlot
          chartId={`${repo.id}:topic:${history.watch.id}:${scope}`}
          series={[{ key: String(history.watch.id), name: history.watch.topic }]}
          style={{ height: 360, width: "100%" }}
          build={({ axis, events }) => rankHistoryOption({
            dates: datesFromHistory([history.points], history.missedDates),
            axis,
            events,
            series: [
              {
                name: history.watch.topic,
                points: history.points,
                limit: history.watch.resultLimit,
                missedDates: history.missedDates,
              },
            ],
          })}
        />
      </ChartCard>

      <Card>
        <h2 className="mb-3 font-medium">Current Topic Results</h2>
        {!results ? (
          <p className="text-sm text-muted-foreground">No topic snapshot yet.</p>
        ) : (
          <TopicResultsTable
            rows={results.rows}
            repositoryId={repo.id}
            trackedGithubId={repo.githubId}
            nameTo={(row) => topicCompetitorPath(repo.id, history.watch.topic, row.result.githubRepositoryId, scope)}
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
