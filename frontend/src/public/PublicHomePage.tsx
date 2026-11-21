import { useEffect, useId, useMemo, useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { Button, Card } from "../components/ui";
import { api } from "../lib/api";
import {
  DEFAULT_LANDING_DIRECTORY,
  OWNER_PAGE_SIZES,
  OWNER_SORTS,
  parseOwnerOffset,
  parseOwnerOrder,
  parseOwnerPageSize,
  parseOwnerSort,
  readLandingDirectoryPrefs,
  writeLandingDirectoryPrefs,
  type LandingDirectoryPrefs,
  type OwnerOrder,
  type OwnerPageSize,
  type OwnerSort,
} from "../lib/landingPrefs";
import type { PublicPage } from "../lib/publicDashboard";
import { cn, formatNumber } from "../lib/utils";

export type PublicOwnerRow = {
  id: string;
  login: string;
  type?: string;
  canonicalPath: string;
  repositories: number;
  stars: number | null;
  forks: number | null;
  watchers: number | null;
  contributors: number | null;
  complete?: boolean;
  contributorsComplete?: boolean;
};

const CAPABILITIES = [
  {
    title: "Traffic history",
    body: "Follow daily views, visitors, clones and unique cloners from saved GitHub traffic.",
  },
  {
    title: "Community stats",
    body: "Compare stars, forks, watchers and contributors across published repositories.",
  },
  {
    title: "Search visibility",
    body: "See how tracked queries rank in GitHub Search over time.",
  },
  {
    title: "Topic rankings",
    body: "Watch topic placement and neighboring repositories from stored snapshots.",
  },
] as const;

const COLUMN_LABELS: Record<OwnerSort, string> = {
  owner: "Owner",
  repositories: "Repositories",
  stars: "Stars",
  forks: "Forks",
  watchers: "Watchers",
  contributors: "Contributors",
};

function ownersUrl(prefs: LandingDirectoryPrefs, after: number) {
  const params = new URLSearchParams({
    sort: prefs.sort,
    order: prefs.order,
    after: String(after),
    limit: String(prefs.limit),
  });
  return `/api/v1/public/owners?${params}`;
}

function metric(value: number | null | undefined) {
  return value == null ? "—" : formatNumber(value);
}

function resolvePrefs(params: URLSearchParams): { prefs: LandingDirectoryPrefs; after: number } {
  const saved = readLandingDirectoryPrefs();
  return {
    prefs: {
      sort: parseOwnerSort(params.get("sort")) ?? saved.sort,
      order: parseOwnerOrder(params.get("order")) ?? saved.order,
      limit: parseOwnerPageSize(params.get("limit")) ?? saved.limit,
    },
    after: parseOwnerOffset(params.get("after")),
  };
}

function writeUrl(
  prefs: LandingDirectoryPrefs,
  after: number,
  setParams: (next: URLSearchParams, opts?: { replace?: boolean }) => void,
  replace = false,
) {
  const next = new URLSearchParams();
  next.set("sort", prefs.sort);
  next.set("order", prefs.order);
  next.set("limit", String(prefs.limit));
  if (after > 0) {
    next.set("after", String(after));
  }
  setParams(next, { replace });
}

function isNormalized(params: URLSearchParams, prefs: LandingDirectoryPrefs, after: number) {
  return (
    params.get("sort") === prefs.sort &&
    params.get("order") === prefs.order &&
    params.get("limit") === String(prefs.limit) &&
    (after === 0 ? !params.has("after") : params.get("after") === String(after))
  );
}

export function PublicHomePage() {
  const ownersId = useId();
  const [params, setParams] = useSearchParams();
  const query = params.toString();
  const { prefs, after } = useMemo(() => resolvePrefs(new URLSearchParams(query)), [query]);
  const normalized = isNormalized(params, prefs, after);
  const [items, setItems] = useState<PublicOwnerRow[]>([]);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [reloadKey, setReloadKey] = useState(0);

  useEffect(() => {
    if (!normalized) {
      writeUrl(prefs, after, setParams, true);
    }
  }, [normalized, prefs, after, setParams]);

  useEffect(() => {
    writeLandingDirectoryPrefs(prefs);
  }, [prefs]);

  useEffect(() => {
    if (!normalized) {
      return;
    }
    const abort = new AbortController();
    setLoading(true);
    setError(null);
    api<PublicPage<PublicOwnerRow>>(ownersUrl(prefs, after), { signal: abort.signal })
      .then((page) => {
        if (abort.signal.aborted) {
          return;
        }
        setItems(page.items);
        setNextCursor(page.nextCursor ?? null);
      })
      .catch((err) => {
        if (!abort.signal.aborted) {
          setItems([]);
          setNextCursor(null);
          setError((err as Error).message || "Unable to load owners");
        }
      })
      .finally(() => {
        if (!abort.signal.aborted) {
          setLoading(false);
        }
      });
    return () => abort.abort();
  }, [normalized, prefs, after, reloadKey]);

  function updateDirectory(next: LandingDirectoryPrefs, nextAfter = 0) {
    writeUrl(next, nextAfter, setParams);
  }

  function toggleSort(column: OwnerSort) {
    const order: OwnerOrder =
      prefs.sort === column ? (prefs.order === "asc" ? "desc" : "asc") : column === "owner" ? "asc" : "desc";
    updateDirectory({ ...prefs, sort: column, order });
  }

  function changeLimit(limit: OwnerPageSize) {
    updateDirectory({ ...prefs, limit });
  }

  function goNext() {
    if (!nextCursor) {
      return;
    }
    updateDirectory(prefs, Number(nextCursor));
  }

  function goPrev() {
    updateDirectory(prefs, Math.max(0, after - prefs.limit));
  }

  function scrollToOwners() {
    document.getElementById(ownersId)?.scrollIntoView({ behavior: "smooth", block: "start" });
  }

  return (
    <div className="space-y-10">
      <section className="space-y-5 py-4 sm:py-8">
        <div className="max-w-3xl space-y-3">
          <h1 className="text-3xl font-semibold tracking-tight sm:text-4xl">
            Understand how your open source projects grow
          </h1>
          <p className="text-base text-muted-foreground sm:text-lg">
            Browse published owner dashboards with saved traffic, community stats, search visibility and topic rankings.
          </p>
          <Button type="button" onClick={scrollToOwners}>
            Explore dashboards
          </Button>
        </div>
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          {CAPABILITIES.map((card) => (
            <Card key={card.title} className="space-y-2 p-4 shadow-none">
              <h2 className="text-sm font-semibold">{card.title}</h2>
              <p className="text-sm text-muted-foreground">{card.body}</p>
            </Card>
          ))}
        </div>
      </section>

      <section id={ownersId} className="space-y-4 scroll-mt-4" aria-labelledby={`${ownersId}-heading`}>
        <div className="flex flex-wrap items-end justify-between gap-3">
          <div>
            <h2 id={`${ownersId}-heading`} className="text-xl font-semibold">
              Owners
            </h2>
            <p className="text-sm text-muted-foreground">Published owners sorted from saved repository snapshots.</p>
          </div>
          <label className="flex items-center gap-2 text-sm">
            <span className="text-muted-foreground">Rows</span>
            <select
              className="min-h-11 rounded-md border bg-card px-2"
              aria-label="Owners page size"
              value={prefs.limit}
              onChange={(event) => changeLimit(Number(event.target.value) as OwnerPageSize)}
            >
              {OWNER_PAGE_SIZES.map((size) => (
                <option key={size} value={size}>
                  {size}
                </option>
              ))}
            </select>
          </label>
        </div>

        {error ? (
          <div role="alert" className="space-y-3 rounded-xl border border-red-200 bg-red-50 p-4">
            <p>{error}</p>
            <Button type="button" onClick={() => setReloadKey((value) => value + 1)}>
              Retry
            </Button>
          </div>
        ) : null}

        <div className="overflow-x-auto rounded-xl border bg-card">
          <table className="w-full min-w-[640px] table-fixed text-sm">
            <caption className="sr-only">Published owners directory</caption>
            <thead>
              <tr className="border-b bg-muted/60 text-left">
                {OWNER_SORTS.map((column) => {
                  const active = prefs.sort === column;
                  const numeric = column !== "owner";
                  return (
                    <th key={column} scope="col" className={cn("p-3 font-medium", numeric && "text-right")}>
                      <button
                        type="button"
                        className="inline-flex min-h-11 items-center gap-1"
                        aria-sort={active ? (prefs.order === "asc" ? "ascending" : "descending") : "none"}
                        onClick={() => toggleSort(column)}
                      >
                        {COLUMN_LABELS[column]}
                        <span aria-hidden="true" className="text-muted-foreground">
                          {active ? (prefs.order === "asc" ? "↑" : "↓") : ""}
                        </span>
                      </button>
                    </th>
                  );
                })}
              </tr>
            </thead>
            <tbody>
              {loading ? (
                <tr>
                  <td colSpan={OWNER_SORTS.length} className="p-6 text-muted-foreground">
                    Loading owners…
                  </td>
                </tr>
              ) : items.length === 0 ? (
                <tr>
                  <td colSpan={OWNER_SORTS.length} className="p-6 text-muted-foreground">
                    No published owners yet.
                  </td>
                </tr>
              ) : (
                items.map((row) => (
                  <tr key={row.id} className="border-t">
                    <th scope="row" className="p-3 text-left font-normal">
                      <Link className="font-medium text-primary hover:underline" to={row.canonicalPath || `/${row.login}`}>
                        {row.login}
                      </Link>
                    </th>
                    <td className="p-3 text-right">{formatNumber(row.repositories)}</td>
                    <td className="p-3 text-right">{metric(row.stars)}</td>
                    <td className="p-3 text-right">{metric(row.forks)}</td>
                    <td className="p-3 text-right">{metric(row.watchers)}</td>
                    <td className="p-3 text-right">{metric(row.contributors)}</td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>

        <div className="flex flex-wrap items-center justify-between gap-3">
          <p className="text-sm text-muted-foreground">
            {after === 0 ? "First page" : `After ${after}`}
            {prefs.sort !== DEFAULT_LANDING_DIRECTORY.sort || prefs.order !== DEFAULT_LANDING_DIRECTORY.order
              ? ` · sorted by ${COLUMN_LABELS[prefs.sort]} ${prefs.order.toUpperCase()}`
              : null}
          </p>
          <div className="flex gap-2">
            <Button type="button" className="bg-card text-foreground border" disabled={after === 0 || loading} onClick={goPrev}>
              Previous
            </Button>
            <Button type="button" className="bg-card text-foreground border" disabled={!nextCursor || loading} onClick={goNext}>
              Next
            </Button>
          </div>
        </div>
      </section>
    </div>
  );
}
