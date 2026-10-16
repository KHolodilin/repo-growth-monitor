import { useEffect, useMemo, useState, type ReactNode } from "react";
import { Link, useNavigate } from "react-router-dom";
import { api, type Dashboard } from "../lib/api";
import { dashboardTrafficOption } from "../lib/dashboardChart";
import { activityClass, cn, formatActivityPresentation, formatGrowth, formatNumber, growthClass } from "../lib/utils";
import { RepositoryAccessNote } from "../components/RepositoryAccessNote";
import { Button, Card, Skeleton } from "../components/ui";
import { loadGitHubAccess, type TokenState } from "../lib/githubAccess";
import { PeriodSelector, usePeriod, type Period } from "../components/PeriodSelector";
import { TrafficKpiCard } from "../components/TrafficKpiCard";
import { ChartCard } from "../components/ChartDownloadMenu";
import { PersistentECharts } from "../components/PersistentECharts";
import { dashboardTrafficChartId, TRAFFIC_SERIES } from "../lib/chartLegend";
import { useTableSort } from "../lib/tableSortPrefs";

const SORT_KEYS = ["visitors", "views", "clones", "stars", "forks", "growth", "discoverability"] as const;

type SortKey = (typeof SORT_KEYS)[number];

const JOB_LABELS: Record<string, string> = {
  TRAFFIC: "Traffic",
  REFERRERS: "Referrers",
  POPULAR_PATHS: "Popular Paths",
  REPOSITORY_STATS: "Repository Stats",
  GROWTH_EVENTS: "Growth Events",
};

export function DashboardPage() {
  const [period, setPeriod] = usePeriod();
  const [data, setData] = useState<Dashboard | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  async function load() {
    const dashboard = await api<Dashboard>(`/api/v1/dashboard?period=${period}`);
    setData(dashboard);
    return dashboard;
  }

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    load()
      .catch((err: Error) => {
        if (!cancelled) {
          setError(err.message);
          setData(null);
        }
      })
      .finally(() => {
        if (!cancelled) {
          setLoading(false);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [period]);

  const collectingFirst = data?.state === "FIRST_COLLECTION" || Boolean(data?.activeCollection);
  useEffect(() => {
    if (!collectingFirst) {
      return;
    }
    const timer = window.setInterval(() => {
      void load().catch(() => undefined);
    }, 2500);
    return () => window.clearInterval(timer);
  }, [collectingFirst, period]);

  useEffect(() => {
    function onCollected() {
      void load().catch((err: Error) => setError(err.message));
    }
    window.addEventListener("repos-collected", onCollected);
    return () => window.removeEventListener("repos-collected", onCollected);
  }, [period]);

  if (error) {
    return <p className="text-red-600">{error}</p>;
  }
  if (loading || !data) {
    return <DashboardSkeleton period={period} onPeriod={setPeriod} />;
  }
  if (data.state === "NO_REPOSITORIES") {
    return <NoRepositoriesState period={period} onPeriod={setPeriod} />;
  }

  return (
    <div className="space-y-4">
      <DashboardHeader data={data} period={period} onPeriod={setPeriod} />
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-5">
        <KpiCard label="Repositories" value={data.summary.repositories} />
        <TrafficKpiCard
          label="Views"
          value={data.summary.views.value}
          growthPercent={data.summary.views.growthPercent}
          period={period}
          series={data.traffic.map((point) => point.views)}
        />
        <TrafficKpiCard
          label="Visitors"
          value={data.summary.visitors.value}
          growthPercent={data.summary.visitors.growthPercent}
          period={period}
          series={data.traffic.map((point) => point.visitors)}
        />
        <TrafficKpiCard
          label="Clones"
          value={data.summary.clones.value}
          growthPercent={data.summary.clones.growthPercent}
          period={period}
          series={data.traffic.map((point) => point.clones)}
        />
        <StarsKpi stars={data.summary.stars} period={period} />
      </div>
      {data.state === "FIRST_COLLECTION" && (
        <Card>
          <h2 className="font-medium">No traffic yet</h2>
          <p className="mt-1 text-sm text-muted-foreground">
            Use the collect button in the header to fetch GitHub stats for the tracked repositories. The chart fills in after the first successful collection.
          </p>
        </Card>
      )}
      <TrafficChart traffic={data.traffic} />
      <PeriodHighlights highlights={data.highlights} />
      <RepositoryTable rows={data.repositories} />
    </div>
  );
}

function DashboardHeader({
  data,
  period,
  onPeriod,
}: {
  data?: Dashboard;
  period: Period;
  onPeriod: (period: Period) => void;
}) {
  return (
    <div className="flex flex-wrap items-start justify-between gap-4">
      <h1 className="text-2xl font-semibold">Portfolio</h1>
      <div className="flex flex-col items-end gap-3">
        {(data?.collectionWarning || data?.partialData?.present) && (
          <div className="text-right text-sm text-muted-foreground">
            {data?.collectionWarning && (
              <div className="text-amber-700">⚠ {data.collectionWarning.message}</div>
            )}
            {data?.partialData?.present && (
              <div className="group relative mt-1 inline-block text-amber-700">
                ⚠ Partial data
                <div className="invisible absolute right-0 z-10 mt-2 w-72 rounded-md border bg-card p-3 text-left text-xs text-foreground shadow-lg group-hover:visible">
                  {data.partialData.message}
                </div>
              </div>
            )}
          </div>
        )}
        <PeriodSelector period={period} onPeriod={onPeriod} />
      </div>
    </div>
  );
}

function KpiCard({ label, value }: { label: string; value: number }) {
  return (
    <Card className="p-3">
      <div className="text-sm text-muted-foreground">{label}</div>
      <div className="mt-1 text-2xl font-semibold">{formatNumber(value)}</div>
    </Card>
  );
}

function StarsKpi({ stars, period }: { stars: { total: number; change: number | null }; period: Period }) {
  const change = stars.change;
  return (
    <Card className="p-3">
      <div className="text-sm text-muted-foreground">Stars</div>
      <div className="mt-1 text-2xl font-semibold">
        {formatNumber(stars.total)} <span className="text-sm font-normal text-muted-foreground">total</span>
      </div>
      {period !== "all" && (
        <div className={cn("mt-1 text-sm font-medium", (change ?? 0) > 0 ? "text-emerald-700" : (change ?? 0) < 0 ? "text-red-700" : "text-muted-foreground")}>
          {change !== null && change !== undefined ? `${change > 0 ? "+" : ""}${formatNumber(change)} in ${period}` : "—"}
        </div>
      )}
    </Card>
  );
}

function TrafficChart({ traffic }: { traffic: Dashboard["traffic"] }) {
  const option = useMemo(() => dashboardTrafficOption(traffic), [traffic]);

  return (
    <ChartCard title="Traffic">
      <PersistentECharts
        chartId={dashboardTrafficChartId()}
        series={TRAFFIC_SERIES}
        option={option}
        style={{ height: 360, width: "100%" }}
        opts={{ renderer: "canvas" }}
      />
    </ChartCard>
  );
}

function PeriodHighlights({ highlights }: { highlights: Dashboard["highlights"] }) {
  const rise = highlights?.rankRise;
  const drop = highlights?.rankDrop;
  const events = highlights?.events;
  const visitors = highlights?.visitors;
  const growth = visitors ? formatGrowth(visitors.growthPercent) : null;
  return (
    <div className="space-y-2 text-sm">
      <HighlightLine label="Rank">
        {rise && <RankLink move={rise} />}
        {drop && <RankLink move={drop} />}
        {!rise && !drop && <span className="text-muted-foreground">No rank changes</span>}
      </HighlightLine>
      <HighlightLine label="Events">
        {events ? (
          <Link className="text-primary hover:underline" to={`/repositories/${events.repositoryId}?tab=growth-events`}>
            {events.count} {events.count === 1 ? "promotion event" : "promotion events"} · {events.repositoryNames.join(", ")}
          </Link>
        ) : (
          <span className="text-muted-foreground">No promotion events</span>
        )}
      </HighlightLine>
      <HighlightLine label="Visitors">
        {visitors && growth ? (
          <Link className="hover:underline" to={`/repositories/${visitors.repositoryId}`}>
            {visitors.fullName} <span className={growthClass(growth.direction)}>{growth.label}</span>
          </Link>
        ) : (
          <span className="text-muted-foreground">No visitor growth</span>
        )}
      </HighlightLine>
    </div>
  );
}

function HighlightLine({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex items-baseline gap-3">
      <span className="w-20 shrink-0 text-muted-foreground">{label}</span>
      <div className="flex flex-col gap-1">{children}</div>
    </div>
  );
}

function RankLink({ move }: { move: NonNullable<Dashboard["highlights"]>["rankRise"] }) {
  if (!move) {
    return null;
  }
  return (
    <Link className="text-primary hover:underline" to={`/repositories/${move.repositoryId}?tab=search`}>
      {move.fullName} · {move.queryName} {move.fromRank} → {move.toRank}
    </Link>
  );
}

function RepositoryTable({ rows }: { rows: Dashboard["repositories"] }) {
  const [tokenState, setTokenState] = useState<TokenState | null>(null);
  const needsAccess = rows.some((row) => row.accessible === false || row.trafficPermissionMissing);
  useEffect(() => {
    if (!needsAccess) {
      return;
    }
    let cancelled = false;
    void loadGitHubAccess().then((next) => {
      if (!cancelled) {
        setTokenState(next.tokenState);
      }
    });
    return () => {
      cancelled = true;
    };
  }, [needsAccess]);
  const navigate = useNavigate();
  const { sortKey, sortDir, toggle } = useTableSort({
    tableId: "dashboard-repositories",
    keys: SORT_KEYS,
    initial: { key: "visitors", dir: "desc" },
  });

  const sorted = useMemo(() => {
    const copy = [...rows];
    copy.sort((left, right) => {
      const leftValue = sortValue(left, sortKey);
      const rightValue = sortValue(right, sortKey);
      if (leftValue === null && rightValue === null) {
        return 0;
      }
      if (leftValue === null) {
        return 1;
      }
      if (rightValue === null) {
        return -1;
      }
      return sortDir === "desc" ? rightValue - leftValue : leftValue - rightValue;
    });
    return copy;
  }, [rows, sortKey, sortDir]);

  return (
    <Card className="overflow-hidden p-0">
      <div className="px-5 pt-5">
        <h2 className="font-medium">Repositories</h2>
      </div>
      <div className="mt-3 overflow-x-auto">
        <table className="w-full min-w-[1000px] text-sm">
          <thead className="text-left text-muted-foreground">
            <tr>
              <th className="px-5 py-2 font-medium">Repository</th>
              <SortHeader label="Visitors" active={sortKey === "visitors"} dir={sortDir} onClick={() => toggle("visitors")} />
              <SortHeader label="Views" active={sortKey === "views"} dir={sortDir} onClick={() => toggle("views")} />
              <SortHeader label="Clones" active={sortKey === "clones"} dir={sortDir} onClick={() => toggle("clones")} />
              <SortHeader label="Stars" active={sortKey === "stars"} dir={sortDir} onClick={() => toggle("stars")} />
              <SortHeader label="Forks" active={sortKey === "forks"} dir={sortDir} onClick={() => toggle("forks")} />
              <SortHeader label="Growth" active={sortKey === "growth"} dir={sortDir} onClick={() => toggle("growth")} />
              <SortHeader label="Disc." active={sortKey === "discoverability"} dir={sortDir} onClick={() => toggle("discoverability")} />
              <th className="px-5 py-2 font-medium">Activity</th>
            </tr>
          </thead>
          <tbody>
            {sorted.map((row) => {
              const growth = formatGrowth(row.growthPercent);
              return (
                <tr
                  key={row.id}
                  className="cursor-pointer border-t hover:bg-muted/60"
                  onClick={() => navigate(`/repositories/${row.id}`)}
                >
                  <td className="max-w-0 px-5 py-3 align-top">
                    <Link
                      className="break-words [overflow-wrap:anywhere] text-primary hover:underline"
                      to={`/repositories/${row.id}`}
                      onClick={(event) => event.stopPropagation()}
                    >
                      {row.fullName}
                    </Link>
                    <RepositoryAccessNote
                      accessible={row.accessible}
                      trafficPermissionMissing={row.trafficPermissionMissing}
                      tokenState={tokenState}
                    />
                    {row.collectionStatus === "PARTIAL" && (
                      <CollectionHint jobs={row.jobs} />
                    )}
                  </td>
                  <td className="px-5 text-right tabular-nums">{formatNumber(row.visitors)}</td>
                  <td className="px-5 text-right tabular-nums">{formatNumber(row.views)}</td>
                  <td className="px-5 text-right tabular-nums">{formatNumber(row.clones)}</td>
                  <td className="px-5 text-right tabular-nums">{formatNumber(row.stars)}</td>
                  <td className="px-5 text-right tabular-nums">{formatNumber(row.forks)}</td>
                  <td className={cn("px-5 text-right tabular-nums font-medium", growthClass(growth?.direction))}>
                    {growth?.label ?? "—"}
                  </td>
                  <td className="px-5 text-right tabular-nums">
                    <DiscoverabilityCell row={row} />
                  </td>
                  <td className={cn("whitespace-nowrap px-5", activityClass(row.activityStatus))}>
                    {formatActivityPresentation(row.activityStatus, row.activityAt)}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </Card>
  );
}

function DiscoverabilityCell({ row }: { row: Dashboard["repositories"][number] }) {
  if (row.discoverabilityPassed == null) {
    return <span>—</span>;
  }
  const tone = discoverabilityTone(row.discoverabilityPassed);
  if (row.discoverabilityTotal == null) {
    return <span className={tone}>{row.discoverabilityPassed}</span>;
  }
  return (
    <Link
      className="hover:underline"
      to={`/repositories/${row.id}?tab=overview&focus=health`}
      onClick={(event) => event.stopPropagation()}
    >
      <span className={tone}>{row.discoverabilityPassed}</span>
      <span>/{row.discoverabilityTotal}</span>
    </Link>
  );
}

function discoverabilityTone(passed: number) {
  if (passed >= 11) {
    return "text-emerald-700";
  }
  if (passed >= 5) {
    return "text-amber-600";
  }
  return "text-red-700";
}

function SortHeader({
  label,
  active,
  dir,
  onClick,
}: {
  label: string;
  active: boolean;
  dir: "asc" | "desc";
  onClick: () => void;
}) {
  return (
    <th className="px-5 py-2 text-right">
      <button type="button" className={cn("font-medium hover:text-foreground", active && "text-foreground")} onClick={onClick}>
        {label}
        {active ? (dir === "desc" ? " ↓" : " ↑") : ""}
      </button>
    </th>
  );
}

function CollectionHint({ jobs }: { jobs: { jobType: string; status: string }[] }) {
  return (
    <span className="group relative ml-2 inline-block align-middle text-amber-700">
      ⚠
      <span className="invisible absolute left-0 z-10 mt-2 w-56 rounded-md border bg-card p-3 text-left text-xs font-normal text-foreground shadow-lg group-hover:visible">
        <div className="mb-2 font-medium">Collection status</div>
        {jobs.map((job) => (
          <div key={job.jobType} className="flex justify-between gap-3 py-0.5">
            <span>{JOB_LABELS[job.jobType] ?? job.jobType}</span>
            <span>{job.status === "SUCCESS" ? "✓" : job.status === "FAILED" ? "✕" : "…"}</span>
          </div>
        ))}
      </span>
    </span>
  );
}

function sortValue(row: Dashboard["repositories"][number], key: SortKey): number | null {
  if (key === "growth") {
    return row.growthPercent;
  }
  if (key === "discoverability") {
    return row.discoverabilityPassed;
  }
  return row[key];
}

function NoRepositoriesState({ period, onPeriod }: { period: Period; onPeriod: (period: Period) => void }) {
  return (
    <div className="space-y-6">
      <DashboardHeader period={period} onPeriod={onPeriod} />
      <Card className="flex flex-col items-start gap-4 py-12">
        <div>
          <h2 className="text-lg font-medium">No repositories tracked yet.</h2>
          <p className="mt-2 max-w-md text-sm text-muted-foreground">
            Select repositories to start collecting GitHub growth data.
          </p>
        </div>
        <Link to="/repositories">
          <Button>Select repositories</Button>
        </Link>
      </Card>
    </div>
  );
}

function DashboardSkeleton({ period, onPeriod }: { period: Period; onPeriod: (period: Period) => void }) {
  return (
    <div className="space-y-6">
      <DashboardHeader period={period} onPeriod={onPeriod} />
      <div className="grid grid-cols-2 gap-4 lg:grid-cols-5">
        {Array.from({ length: 5 }).map((_, index) => (
          <Skeleton key={index} className="h-28" />
        ))}
      </div>
      <Skeleton className="h-96" />
      <Skeleton className="h-64" />
    </div>
  );
}
