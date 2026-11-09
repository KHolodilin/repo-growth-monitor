import { PERIODS, PeriodSelector, usePeriod, type Period } from "../components/PeriodSelector";
import { useEffect, useState } from "react";
import { useParams, useSearchParams } from "react-router-dom";
import { api, type Repository, type SnapshotHistory } from "../lib/api";
import type { SnapshotChartKind } from "../lib/snapshotChartPrefs";
import { cn } from "../lib/utils";
import { useServicePaths } from "../lib/servicePathPrefs";
import { PageBreadcrumb } from "../components/PageBreadcrumb";
import { ServicePathToggle } from "../components/ServicePaths";
import { SnapshotHistoryView } from "../components/SnapshotHistoryView";
import { Skeleton } from "../components/ui";


const KIND_LABEL: Record<SnapshotChartKind, string> = {
  referrers: "Referrers",
  paths: "Popular Paths",
};

const CHART_TITLE: Record<SnapshotChartKind, string> = {
  referrers: "Referrer Snapshot History",
  paths: "Path Snapshot History",
};

function parseKind(value: string | null): SnapshotChartKind {
  return value === "paths" ? "paths" : "referrers";
}

function historyPeriod(raw: string | null, days: string | null, saved: Period): Period {
  if (raw) return PERIODS.includes(raw as Period) ? raw as Period : saved;
  if (days === "1") return "1d";
  if (days === "7") return "7d";
  if (days === "14") return "30d";
  return saved;
}

export function TrafficHistoryPage() {
  const { id } = useParams();
  const [searchParams, setSearchParams] = useSearchParams();
  const kind = parseKind(searchParams.get("kind"));
  const [savedPeriod, savePeriod] = usePeriod(id);
  const period = historyPeriod(searchParams.get("period"), searchParams.get("days"), savedPeriod);
  function selectPeriod(next: Period) {
    savePeriod(next);
    setSearchParams({ kind, period: next }, { replace: true });
  }
  const { showServicePaths, setShowServicePaths } = useServicePaths(id);
  const includeService = kind === "paths" && showServicePaths;
  const [repo, setRepo] = useState<Repository | null>(null);
  const [history, setHistory] = useState<SnapshotHistory | null>(null);
  const [loadedContext, setLoadedContext] = useState<string | null>(null);
  const context = `${id}:${kind}`;
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!id) {
      return;
    }
    let cancelled = false;
    setError(null);
    Promise.all([
      api<Repository>(`/api/v1/admin/repositories/${id}`),
      api<SnapshotHistory>(
        `/api/v1/admin/repositories/${id}/traffic-history?kind=${kind}&period=${period}&includeServicePaths=${includeService}`,
      ),
    ])
      .then(([repository, data]) => {
        if (!cancelled) {
          setRepo(repository);
          setHistory(data);
          setLoadedContext(`${id}:${kind}`);
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
  }, [id, kind, period, includeService]);

  if (error) {
    return <p className="text-red-600">{error}</p>;
  }
  if (!repo || !history || loadedContext !== context) {
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
                  `/repositories/${repositoryId}/traffic/history?kind=${kind}&period=${period}`,
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
                kind === option ? "bg-card text-foreground shadow-xs" : "text-muted-foreground hover:text-foreground",
              )}
              onClick={() => setSearchParams({ kind: option, period }, { replace: true })}
            >
              {KIND_LABEL[option]}
            </button>
          ))}
        </div>
        <div className="flex w-full max-w-full flex-wrap items-center gap-3 md:w-auto">
          {kind === "paths" && (
            <div className="text-xs text-muted-foreground">
              <ServicePathToggle checked={showServicePaths} onChange={setShowServicePaths} />
            </div>
          )}
          <div role="group" aria-label="History period" className="w-full md:w-auto"><PeriodSelector fitMobile period={period} onPeriod={selectPeriod} /></div>
        </div>
      </div>
      <SnapshotHistoryView key={`${repo.id}:${kind}`} title={CHART_TITLE[kind]} kind={kind} history={history} period={period} />
    </div>
  );
}
