import { useChartVisibility } from "../lib/chartVisibility";
import { PeriodSelector, usePeriod } from "../components/PeriodSelector";
import { NavigationButton } from "../components/NavigationButton";
import { RankSummary } from "../components/RankSummary";
import { useEffect, useRef, useState } from "react";
import { useParams, useSearchParams } from "react-router-dom";
import { api, type Repository, type TopicHistory, type TopicRunResults } from "../lib/api";
import { waitForCollectionRequest } from "../lib/collectionRequest";
import { useMutationAccess } from "../lib/mutationAccess";
import { formatSyncTime } from "../lib/utils";
import { datesFromHistory, rankHistoryOption } from "../lib/rankChart";
import { writeTopicScope } from "../lib/topicScope";
import { ChartCard } from "../components/ChartDownloadMenu";
import { ChartModePlot } from "../components/PersistentECharts";
import { Button, Card, Skeleton } from "../components/ui";
import { PageBreadcrumb } from "../components/PageBreadcrumb";
import { TopicResultsTable } from "../components/TopicResultsTable";
import { topicCompetitorPath } from "./TopicCompetitorCardPage";
import { ownerPath, repoHref, repoPath } from "../lib/repoPaths";
import { useResolvedRepositoryId } from "../lib/useResolvedRepositoryId";

function parseScope(value: string | null): "all" | "language" {
  return value === "language" ? "language" : "all";
}

export function TopicDetailsPage() {
  const { owner = "", repository: repositoryName = "", topic } = useParams();
  const { id: repositoryId, error: resolveError } = useResolvedRepositoryId(owner, repositoryName);
  const [period, setPeriod] = usePeriod(repositoryId);
  const [searchParams, setSearchParams] = useSearchParams();
  const { allowMutations } = useMutationAccess();
  const scope = parseScope(searchParams.get("scope"));
  const [repo, setRepo] = useState<Repository | null>(null);
  const [history, setHistory] = useState<TopicHistory | null>(null);
  const [results, setResults] = useState<TopicRunResults | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [running, setRunning] = useState(false);
  const [selected, setSelected] = useState<number[]>([]);
  const visibility = useChartVisibility(`${repositoryId}:topic:${history?.watch.id}:${scope}`);
  const [competitors, setCompetitors] = useState<{ githubRepositoryId: number; fullName: string; points: { date: string; position: number }[] }[]>([]);
  const generation = useRef(0);

  const topicName = topic ? decodeURIComponent(topic) : "";

  async function load() {
    if (!repositoryId || !topicName) {
      return;
    }
    const version = generation.current;
    const [repository, topicHistory] = await Promise.all([
      api<Repository>(`/api/v1/admin/repositories/${repositoryId}`),
      api<TopicHistory>(
        `/api/v1/admin/repositories/${repositoryId}/topics/${encodeURIComponent(topicName)}/history?scope=${scope}`,
      ),
    ]);
    if (version !== generation.current) return;
    setRepo(repository);
    setHistory(topicHistory);
    const [nextResults, nextSeries] = await Promise.all([
      api<TopicRunResults>(`/api/v1/admin/repositories/${repositoryId}/topics/${encodeURIComponent(topicName)}/results?scope=${scope}`).catch(() => null),
      api<typeof competitors>(`/api/v1/admin/repositories/${repositoryId}/topics/${encodeURIComponent(topicName)}/series?scope=${scope}`).catch(() => []),
    ]);
    if (version !== generation.current) return;
    setResults(nextResults);
    setCompetitors(nextSeries);

  }

  useEffect(() => {
    generation.current++;
    setSelected([]);
    setCompetitors([]);
    setHistory(null);
    setResults(null);
    setError(null);
    const version = generation.current;
    load().catch((err: Error) => { if (version === generation.current) setError(err.message); });
    return () => { generation.current++; };
  }, [repositoryId, topicName, scope]);

  const searchBusy =
    history?.searchStatus === "RUNNING"
    || history?.searchStatus === "READY"
    || history?.searchStatus === "RETRY";

  useEffect(() => {
    if (!searchBusy) {
      return;
    }
    const timer = window.setInterval(() => {
      void load().catch(() => undefined);
    }, 2500);
    return () => window.clearInterval(timer);
  }, [searchBusy, repositoryId, topicName, scope]);

  async function runNow() {
    if (!allowMutations || !history || running || searchBusy) {
      return;
    }
    setRunning(true);
    try {
      const created = await api<{ requestId?: string }>(`/api/v1/admin/topic-watches/${history.watch.id}/run`, { method: "POST" });
      if (created?.requestId) {
        await waitForCollectionRequest(created.requestId);
      }
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

  if (resolveError) {
    return <p className="text-red-600">{resolveError}</p>;
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
  const languageLabel = repo.language || "Language";
  const topicUrl =
    scope === "language" && repo.language
      ? `https://github.com/topics/${encodeURIComponent(history.watch.topic)}?o=desc&s=stars&l=${encodeURIComponent(repo.language)}`
      : `https://github.com/topics/${encodeURIComponent(history.watch.topic)}?o=desc&s=stars`;

  const selectedSeries = competitors.filter(item => selected.includes(item.githubRepositoryId));
  const rankSeries = [{ key: `repository:${repo.githubId}`, name: repo.fullName, expected: history.watch.enabled, points: history.points, limit: history.watch.resultLimit, missedDates: history.missedDates },
    ...selectedSeries.map(item => ({ key: `repository:${item.githubRepositoryId}`, name: item.fullName, expected: false, points: item.points, limit: history.watch.resultLimit }))];
  const chartSeries = [{ key: `repository:${repo.githubId}`, name: repo.fullName }, ...selectedSeries.map(item => ({ key: `repository:${item.githubRepositoryId}`, name: item.fullName }))];
  const unavailable = selected.filter(id => !competitors.some(item => item.githubRepositoryId === id && item.points.length > 0));
  return (
    <div className="space-y-6">
      <header className="grid grid-cols-1 items-start gap-x-4 gap-y-2 sm:grid-cols-[minmax(0,1fr)_auto]">
        <div className="sm:col-start-1 sm:row-start-1">
          <PageBreadcrumb
            items={[
              { label: "Home", to: "/" },
              { label: repo.owner.login, to: ownerPath(repo.owner.login) },
              {
                label: repo.name,
                ...(allowMutations
                  ? {
                      repoSwitcher: {
                        currentId: repo.id,
                        hrefFor: (next: typeof repo) => repoHref(next.owner.login, next.name, "topics"),
                      },
                    }
                  : {}),
              },
              { label: "Topics Visibility", to: `${repoHref(repo.owner.login, repo.name, "topics")}${scope === "language" ? "&scope=language" : ""}` },
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

      <div className="flex flex-col gap-3 md:flex-row md:items-center md:justify-between">
      {repo.language && (
        <div className="inline-flex w-fit max-w-full self-start rounded-lg border bg-muted p-1">
          {(["all", "language"] as const).map((key) => (
            <NavigationButton
              key={key}
              label={key === "all" ? "All" : languageLabel}
              truncate={key === "language"}
              aria-pressed={scope === key}
              type="button"
              className={`min-h-11 max-w-[min(16rem,65vw)] md:min-h-0 ${
                scope === key ? "bg-card text-foreground shadow-xs" : "text-muted-foreground hover:text-foreground"
              }`}
              onClick={() => setScope(key)}
            />
          ))}
        </div>
      )}

      <div role="group" aria-label="History period" className="md:ml-auto"><PeriodSelector fitMobile period={period} onPeriod={setPeriod} /></div>
      </div>

      <RankSummary current={history.currentRank} best={history.bestRank} limit={history.watch.resultLimit} change7d={history.change7d} change30d={history.change30d} total={history.totalResults} />

      <ChartCard
        title="Rank History"
        repositoryId={repo.id}
        period={period}
        context={`repository:${repo.id}:topic:${history.watch.id}`}
        mobileSeries={[]}
      >
        {unavailable.length > 0 && <p role="status" className="mb-2 text-sm text-muted-foreground">No rank history for one or more selected competitors.</p>}
        <ChartModePlot
          exportTitle="Rank History"
          chartId={`${repo.id}:topic:${history.watch.id}:${scope}`}
          series={chartSeries}
          persistSelection={false}
          style={{ height: 360, width: "100%" }}
          build={({ axis, events }) => rankHistoryOption({
            dates: datesFromHistory(rankSeries.map(item => item.points), history.missedDates),
            axis,
            period,
            events,
            series: rankSeries,
            legend: true,
            legendToggle: false,
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
            selectedIds={selected.filter(id => visibility.isVisible(`repository:${id}`))}
            onToggle={id => !visibility.isVisible(`repository:${id}`) && selected.includes(id) ? visibility.reveal([`repository:${id}`]) : setSelected(current => current.includes(id) ? current.filter(value => value !== id) : [...current, id])}
            nameTo={(row) => topicCompetitorPath(repoPath(repo.owner.login, repo.name), history.watch.topic, row.result.githubRepositoryId, scope)}
          />
        )}
      </Card>
    </div>
  );
}
