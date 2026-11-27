import { useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { api, type GrowthEvent } from "../lib/api";
import {
  publicBase,
  relativeRepositoryPath,
  type PublicOverview,
  type PublicRow,
  type PublicPage,
} from "../lib/publicDashboard";
import { SnapshotCard } from "../components/SnapshotCards";
import type { Period } from "../components/PeriodSelector";
export type Definition = {
  id: string;
  repositoryId: string;
  name?: string;
  query?: string;
  topic?: string;
  language?: string;
  resultLimit?: number;
};
export function PublicModules({
  overview,
  rows,
  tab,
  period,
  events,
}: {
  overview: PublicOverview;
  rows: PublicRow[];
  tab: string;
  period: Period;
  events: GrowthEvent[];
}) {
  const active = useRef<AbortController | null>(null);
  const loadingMore = useRef(false);
  const [data, setData] = useState<Definition[]>([]);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [moreBusy, setMoreBusy] = useState(false);
  const [more, setMore] = useState<string | null>(null);
  const base = publicBase(overview.owner.id, overview.repository?.id);
  const kind = tab === "topics" ? "topics" : "search-queries";
  const definitionsKey = tab === "search" || tab === "topics" ? `${base}:${kind}` : "idle";
  const [seenDefinitionsKey, setSeenDefinitionsKey] = useState(definitionsKey);
  if (definitionsKey !== seenDefinitionsKey) {
    setSeenDefinitionsKey(definitionsKey);
    if (tab === "search" || tab === "topics") {
      setData([]);
      setMore(null);
      setBusy(true);
      setMoreBusy(false);
      setError("");
    }
  }
  useEffect(() => {
    if (tab !== "search" && tab !== "topics") return;
    const abort = new AbortController();
    active.current = abort;
    loadingMore.current = false;
    api<PublicPage<Definition>>(`${base}/${kind}?limit=100`, { signal: abort.signal })
      .then((page) => {
        if (!abort.signal.aborted) {
          setData(page.items);
          setMore(page.nextCursor ?? null);
          setError("");
        }
      })
      .catch((err: unknown) => {
        if (!abort.signal.aborted) {
          setError(err instanceof Error ? err.message : String(err));
        }
      })
      .finally(() => {
        if (!abort.signal.aborted) setBusy(false);
      });
    return () => abort.abort();
  }, [base, kind, tab]);
  if (tab === "sources") return <PublicSourceCards overview={overview} period={period} />;
  if (tab === "events")
    return (
      <section className="space-y-3">
        <h2 className="text-lg font-semibold">Recent Growth Events</h2>
        {events.length ? (
          <ul className="space-y-2">
            {events.map((event) => (
              <li key={event.id} className="rounded-lg border bg-card p-4">
                <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
                  <time dateTime={event.eventAt}>{event.eventAt.slice(0, 10)}</time>
                  <span>{event.category}</span>
                  <span>{event.type}</span>
                </div>
                <h3 className="mt-1 break-words font-medium">{event.title}</h3>
              </li>
            ))}
          </ul>
        ) : (
          <p role="status" className="text-sm text-muted-foreground">
            No growth events in this period. An administrator can configure collection and add
            promotion events.
          </p>
        )}
      </section>
    );
  if (tab !== "search" && tab !== "topics")
    return <p role="status">This section is not available.</p>;
  const title = tab === "search" ? "Search Visibility" : "Topics Visibility";
  return (
    <section className="space-y-3">
      <h2 className="text-lg font-semibold">{title}</h2>
      {busy ? (
        <p role="status">Loading saved rankings…</p>
      ) : error && !data.length ? (
        <p role="alert">{error}</p>
      ) : !data.length ? (
        <p role="status" className="text-sm text-muted-foreground">
          No published {tab === "search" ? "search queries" : "topic watches"}.
        </p>
      ) : (
        <div className="overflow-x-auto rounded-lg border">
          <table className="w-full min-w-[640px] text-sm">
            <thead className="text-left text-muted-foreground">
              <tr>
                <th className="px-4 py-2 font-medium">
                  {tab === "search" ? "Search Query" : "Topic"}
                </th>
                <th className="px-4 py-2 font-medium">Details</th>
              </tr>
            </thead>
            <tbody>
              {data.map((definition) => {
                const name =
                  rows.find((row) => row.id === definition.repositoryId)?.name ??
                  overview.repository?.name;
                if (!name) return null;
                const label = `${definition.name ?? definition.topic}${definition.language ? ` · ${definition.language}` : ""}`;
                return (
                  <tr key={definition.id} className="border-t">
                    <td className="px-4 py-3 align-top">
                      <Link
                        className="break-words text-primary underline"
                        to={`/${encodeURIComponent(overview.owner.login)}/${encodeURIComponent(name)}/analytics/${tab}/${definition.id}?period=${period}`}
                      >
                        {label}
                      </Link>
                    </td>
                    <td className="px-4 py-3 align-top text-muted-foreground">
                      {definition.query ??
                        (definition.resultLimit != null
                          ? `Top ${definition.resultLimit}`
                          : "Saved ranking")}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
      {error && data.length > 0 ? (
        <p role="alert" className="text-sm text-red-700">
          {error}
        </p>
      ) : null}
      {more && (
        <button
          type="button"
          className="min-h-11 rounded border px-4 disabled:opacity-60"
          disabled={moreBusy}
          aria-busy={moreBusy}
          onClick={() => {
            if (loadingMore.current || moreBusy || !more) return;
            const abort = active.current;
            if (!abort || abort.signal.aborted) return;
            loadingMore.current = true;
            setMoreBusy(true);
            setError("");
            const cursor = more;
            api<PublicPage<Definition>>(`${base}/${kind}?after=${cursor}&limit=100`, {
              signal: abort.signal,
            })
              .then((page) => {
                if (abort.signal.aborted || active.current !== abort) return;
                setData((previous) => [...previous, ...page.items]);
                setMore(page.nextCursor ?? null);
              })
              .catch((err: unknown) => {
                if (abort.signal.aborted || active.current !== abort) return;
                setError(err instanceof Error ? err.message : String(err));
              })
              .finally(() => {
                if (active.current === abort) {
                  loadingMore.current = false;
                  setMoreBusy(false);
                }
              });
          }}
        >
          {moreBusy ? "Loading…" : "Load more"}
        </button>
      )}
    </section>
  );
}
function PublicSourceCards({ overview, period }: { overview: PublicOverview; period: Period }) {
  type Source = { id: string; label: string; views: number; visitors: number; observedAt?: string };
  const [sources, setSources] = useState<{ referrers: Source[]; paths: Source[] }>({
    referrers: [],
    paths: [],
  });
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(true);
  const base = publicBase(overview.owner.id, overview.repository?.id);
  const sourcesKey = `${base}:${overview.modules.referrers}:${overview.modules.paths}`;
  const [seenSourcesKey, setSeenSourcesKey] = useState(sourcesKey);
  if (sourcesKey !== seenSourcesKey) {
    setSeenSourcesKey(sourcesKey);
    setBusy(true);
    setError("");
    setSources({ referrers: [], paths: [] });
  }
  useEffect(() => {
    const abort = new AbortController();
    Promise.all([
      overview.modules.referrers
        ? api<PublicPage<Source>>(`${base}/referrers?limit=100`, { signal: abort.signal })
        : Promise.resolve({ items: [] }),
      overview.modules.paths
        ? api<PublicPage<Source>>(`${base}/paths?limit=100`, { signal: abort.signal })
        : Promise.resolve({ items: [] }),
    ])
      .then(([referrers, paths]) => {
        if (!abort.signal.aborted) setSources({ referrers: referrers.items, paths: paths.items });
      })
      .catch((err: unknown) => {
        if (!abort.signal.aborted) {
          setError(err instanceof Error ? err.message : String(err));
        }
      })
      .finally(() => {
        if (!abort.signal.aborted) setBusy(false);
      });
    return () => abort.abort();
  }, [base, overview]);
  if (busy) return <p role="status">Loading source snapshots…</p>;
  if (error) return <p role="alert">{error}</p>;
  const prefixes = [overview.canonicalPath, ...(overview.repository?.pathPrefixes ?? [])];
  const root = overview.canonicalPath;
  return (
    <div className="grid gap-4 md:grid-cols-2">
      {overview.modules.referrers && (
        <SnapshotCard
          tableId="top-referrers"
          repositoryId={`public:repository:${overview.repository?.id}`}
          title="Top Referrers"
          firstColumn="Source"
          rows={sources.referrers.map((row) => ({
            key: row.label,
            title: row.label,
            views: row.views,
            visitors: row.visitors,
          }))}
          snapshotAt={sources.referrers[0]?.observedAt}
          snapshotLabel="No published snapshot rows"
          historyTo={`${root}/analytics/referrers?period=${period}`}
          icons
        />
      )}
      {overview.modules.paths && (
        <SnapshotCard
          tableId="popular-paths"
          repositoryId={`public:repository:${overview.repository?.id}`}
          title="Popular Paths"
          firstColumn="Path"
          rows={sources.paths.map((row) => ({
            key: row.label,
            title: relativeRepositoryPath(row.label, prefixes),
            views: row.views,
            visitors: row.visitors,
          }))}
          snapshotAt={sources.paths[0]?.observedAt}
          snapshotLabel="No published snapshot rows"
          historyTo={`${root}/analytics/paths?period=${period}`}
        />
      )}
    </div>
  );
}
