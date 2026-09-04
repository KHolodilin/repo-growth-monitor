import { useEffect, useState } from "react";
import { useParams, useSearchParams } from "react-router-dom";
import { api, type Repository, type SnapshotHistory } from "../lib/api";
import type { SnapshotChartKind } from "../lib/snapshotChartPrefs";
import { cn } from "../lib/utils";
import { PageBreadcrumb } from "../components/PageBreadcrumb";
import { SnapshotHistoryChart } from "../components/SnapshotHistoryChart";
import { SnapshotHistoryTable } from "../components/SnapshotHistoryTable";
import { Skeleton } from "../components/ui";

const DAYS = [1, 7, 14] as const;
const DEFAULT_DAYS = 14;

const KIND_LABEL: Record<SnapshotChartKind, string> = {
  referrers: "Referrers",
  paths: "Popular Paths",
};

const CHART_TITLE: Record<SnapshotChartKind, string> = {
  referrers: "Referrer Traffic (by day)",
  paths: "Path Traffic (by day)",
};

function parseKind(value: string | null): SnapshotChartKind {
  return value === "paths" ? "paths" : "referrers";
}

function parseDays(value: string | null) {
  const parsed = Number(value);
  return DAYS.find((option) => option === parsed) ?? DEFAULT_DAYS;
}

export function TrafficHistoryPage() {
  const { id } = useParams();
  const [searchParams, setSearchParams] = useSearchParams();
  const kind = parseKind(searchParams.get("kind"));
  const days = parseDays(searchParams.get("days"));
  const [repo, setRepo] = useState<Repository | null>(null);
  const [history, setHistory] = useState<SnapshotHistory | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!id) {
      return;
    }
    let cancelled = false;
    setError(null);
    Promise.all([
      api<Repository>(`/api/v1/repositories/${id}`),
      api<SnapshotHistory>(`/api/v1/repositories/${id}/traffic-history?kind=${kind}&days=${days}`),
    ])
      .then(([repository, data]) => {
        if (!cancelled) {
          setRepo(repository);
          setHistory(data);
        }
      })
      .catch((err: Error) => {
        if (!cancelled) {
          setError(err.message);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [id, kind, days]);

  if (error) {
    return <p className="text-red-600">{error}</p>;
  }
  if (!repo || !history) {
    return (
      <div className="space-y-4">
        <Skeleton className="h-7 w-72" />
        <Skeleton className="h-96" />
        <Skeleton className="h-64" />
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <header className="space-y-0">
        <PageBreadcrumb
          items={[
            { label: "Portfolio", to: "/dashboard" },
            {
              label: repo.fullName,
              repoSwitcher: {
                currentId: repo.id,
                hrefFor: (repositoryId) =>
                  `/repositories/${repositoryId}/traffic/history?kind=${kind}&days=${days}`,
              },
            },
            { label: "Traffic", to: `/repositories/${repo.id}` },
            { label: "History" },
          ]}
        />
      </header>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="inline-flex rounded-lg border bg-muted p-1">
          {(["referrers", "paths"] as const).map((option) => (
            <button
              key={option}
              type="button"
              className={cn(
                "rounded-md px-3 py-1.5 text-sm font-medium",
                kind === option ? "bg-card text-foreground shadow-sm" : "text-muted-foreground hover:text-foreground",
              )}
              onClick={() => setSearchParams({ kind: option, days: String(days) }, { replace: true })}
            >
              {KIND_LABEL[option]}
            </button>
          ))}
        </div>
        <div className="inline-flex rounded-lg border bg-muted p-1">
          {DAYS.map((option) => (
            <button
              key={option}
              type="button"
              className={cn(
                "rounded-md px-3 py-1.5 text-sm font-medium",
                days === option ? "bg-card text-foreground shadow-sm" : "text-muted-foreground hover:text-foreground",
              )}
              onClick={() => setSearchParams({ kind, days: String(option) }, { replace: true })}
            >
              {option}d
            </button>
          ))}
        </div>
      </div>
      <SnapshotHistoryChart title={CHART_TITLE[kind]} kind={kind} history={history} />
      <SnapshotHistoryTable kind={kind} history={history} />
      <div className="text-xs text-muted-foreground">
        <span className="font-medium text-foreground">About data.</span> GitHub reports referrers and paths as a rolling
        14-day window, so the table shows the stored snapshot values and the chart shows what each snapshot gained.
      </div>
    </div>
  );
}
