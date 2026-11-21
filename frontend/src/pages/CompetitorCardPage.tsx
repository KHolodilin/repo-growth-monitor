import { PeriodSelector, usePeriod } from "../components/PeriodSelector";
import { useEffect, useState } from "react";
import { useParams } from "react-router-dom";
import { ChartCard } from "../components/ChartDownloadMenu";
import { PageBreadcrumb, type BreadcrumbItem } from "../components/PageBreadcrumb";
import { PersistentECharts } from "../components/PersistentECharts";
import { api, type CompetitorCard, type Repository, type SearchHistory } from "../lib/api";
import { storedCountOption } from "../lib/searchSeriesChart";
import { useMutationAccess } from "../lib/mutationAccess";
import { ownerPath, repoHref, repoPath } from "../lib/repoPaths";
import { useResolvedRepositoryId } from "../lib/useResolvedRepositoryId";

const COMPETITOR_SERIES = [
  { key: "Stars", name: "Stars" },
  { key: "Forks", name: "Forks" },
] as const;

export function CompetitorCardPage() {
  const params = useParams();
  const { owner = "", repository: repositoryName = "" } = params;
  const { id: resolvedId, error: resolveError } = useResolvedRepositoryId(owner, repositoryName);
  const { allowMutations } = useMutationAccess();
  const repositoryId = Number(resolvedId);
  const queryId = Number(params.queryId);
  const githubRepositoryId = Number(params.githubRepositoryId);
  const [repo, setRepo] = useState<Repository | null>(null);
  const [history, setHistory] = useState<SearchHistory | null>(null);
  const [card, setCard] = useState<CompetitorCard | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!repositoryId || !queryId || !githubRepositoryId) return;
    let cancelled = false;
    setError(null);
    Promise.all([
      api<Repository>(`/api/v1/admin/repositories/${repositoryId}`),
      api<SearchHistory>(`/api/v1/admin/search-queries/${queryId}/history`),
      api<CompetitorCard>(`/api/v1/admin/search-queries/${queryId}/competitors/${githubRepositoryId}`),
    ])
      .then(([repository, queryHistory, competitor]) => {
        if (cancelled) return;
        setRepo(repository);
        setHistory(queryHistory);
        setCard(competitor);
      })
      .catch((reason: unknown) => {
        if (!cancelled) setError(reason instanceof Error ? reason.message : "Failed to load the competitor card");
      });
    return () => {
      cancelled = true;
    };
  }, [repositoryId, queryId, githubRepositoryId]);

  if (resolveError || error) return <p className="text-sm text-destructive">{resolveError ?? error}</p>;
  if (!repo || !history || !card) return <p className="text-sm text-muted-foreground">Loading competitor card...</p>;

  const base = repoPath(repo.owner.login, repo.name);
  return (
    <CompetitorCardView
      items={[
        { label: "Home", to: "/" },
        { label: repo.owner.login, to: ownerPath(repo.owner.login) },
        {
          label: repo.name,
          ...(allowMutations
            ? {
                repoSwitcher: {
                  currentId: repo.id,
                  hrefFor: (next: typeof repo) => repoHref(next.owner.login, next.name, "search"),
                },
              }
            : {}),
        },
        { label: "Search Visibility", to: repoHref(repo.owner.login, repo.name, "search") },
        { label: history.query.query, to: `${base}/search-queries/${history.query.id}` },
      ]}
      card={card}
      collectionDates={history.query.enabled === false ? [] : history.points.map(point => point.date)}
      missedCollectionDates={history.query.enabled === false ? [] : history.missedDates}
      chartId={`${repositoryId}:competitor:${queryId}:${githubRepositoryId}`}
      repositoryId={repo.id}
    />
  );
}

export function CompetitorCardView({
  items,
  card,
  chartId,
  repositoryId,
  collectionDates = [],
  missedCollectionDates = [],
}: {
  items: BreadcrumbItem[];
  card: CompetitorCard;
  chartId: string;
  repositoryId: number;
  collectionDates?: string[];
  missedCollectionDates?: string[];
}) {
  const [period, setPeriod] = usePeriod(String(repositoryId));
  const passed = card.discoverability.filter((item) => item.passed).length;
  const option = storedCountOption({
    period,
    dates: card.points.map((point) => point.date),
    series: [
      { name: "Stars", data: card.points.map((point) => point.stars) },
      { name: "Forks", data: card.points.map((point) => point.forks) },
    ],
  });

  return (
    <div className="space-y-6">
      <header>
        <PageBreadcrumb items={items} />
        <h1 className="mt-2 break-all text-xl font-semibold">{card.fullName}</h1>
        <a className="mt-1 inline-block text-sm text-primary hover:underline" href={card.htmlUrl} target="_blank" rel="noreferrer">
          Open on GitHub
        </a>
      </header>

      <section className="rounded-lg border bg-card p-4">
        <h2 className="text-sm font-medium">Description</h2>
        <p className="mt-2 text-sm text-muted-foreground">{card.description?.trim() || "No description."}</p>
        <h2 className="mt-4 text-sm font-medium">Topics</h2>
        {card.topics.length === 0 ? (
          <p className="mt-2 text-sm text-muted-foreground">No topics yet.</p>
        ) : (
          <ul className="mt-2 flex flex-wrap gap-2">
            {card.topics.map((topic) => (
              <li key={topic} className="inline-flex rounded-full bg-[#ddf4ff] px-2.5 py-0.5 text-xs font-medium text-[#0969da]">
                {topic}
              </li>
            ))}
          </ul>
        )}
      </section>

      <section className="rounded-lg border bg-card p-4">
        <h2 className="text-sm font-medium">
          Discoverability {passed}/{card.discoverability.length}
        </h2>
        <ul className="mt-3 space-y-1 text-sm">
          {card.discoverability.map((item) => (
            <li key={item.label}>
              {item.passed ? "Pass" : "Miss"} · {item.label}
            </li>
          ))}
        </ul>
      </section>

      <div role="group" aria-label="History period" className="flex justify-end"><PeriodSelector fitMobile period={period} onPeriod={setPeriod} /></div>
      <ChartCard
        title="Stars and forks"
        repositoryId={repositoryId}
        period={period}
        context={`repository:${repositoryId}:competitor`}
        mobileSeries={COMPETITOR_SERIES}
      >
        <PersistentECharts exportTitle="Stars and forks" chartId={chartId} series={COMPETITOR_SERIES} option={{ ...option, collectionDates, missedCollectionDates }} style={{ height: 320 }} />
      </ChartCard>
    </div>
  );
}
