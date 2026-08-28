import { useEffect, useMemo, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import ReactECharts from "echarts-for-react";
import { api, type Dashboard } from "../lib/api";
import { activityClass, cn, formatActivityPresentation, formatGrowth, formatNumber, formatSyncTime, growthClass } from "../lib/utils";
import { Button, Card, Skeleton } from "../components/ui";
import { PeriodSelector, type Period } from "../components/PeriodSelector";

type SortKey = "visitors" | "views" | "clones" | "stars" | "growth";

const JOB_LABELS: Record<string, string> = {
  TRAFFIC: "Traffic",
  REFERRERS: "Referrers",
  POPULAR_PATHS: "Popular Paths",
  REPOSITORY_STATS: "Repository Stats",
};

export function DashboardPage() {
  const [period, setPeriod] = useState<Period>("30d");
  const [data, setData] = useState<Dashboard | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    api<Dashboard>(`/api/v1/dashboard?period=${period}`)
      .then((dashboard) => {
        if (!cancelled) {
          setData(dashboard);
        }
      })
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

  if (error) {
    return <p className="text-red-600">{error}</p>;
  }
  if (loading || !data) {
    return <DashboardSkeleton period={period} onPeriod={setPeriod} />;
  }
  if (data.state === "NO_REPOSITORIES") {
    return <NoRepositoriesState period={period} onPeriod={setPeriod} />;
  }
  if (data.state === "FIRST_COLLECTION") {
    return <FirstCollectionState data={data} period={period} onPeriod={setPeriod} />;
  }

  return (
    <div className="space-y-6">
      <DashboardHeader data={data} period={period} onPeriod={setPeriod} />
      <div className="grid grid-cols-2 gap-4 lg:grid-cols-5">
        <KpiCard label="Repositories" value={data.summary.repositories} />
        <TrafficKpi label="Views" metric={data.summary.views} period={period} />
        <TrafficKpi label="Visitors" metric={data.summary.visitors} period={period} />
        <TrafficKpi label="Clones" metric={data.summary.clones} period={period} />
        <StarsKpi stars={data.summary.stars} period={period} />
      </div>
      <TrafficChart traffic={data.traffic} />
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
  const sync = formatSyncTime(data?.lastSyncAt);
  return (
    <div className="flex flex-wrap items-start justify-between gap-4">
      <h1 className="text-2xl font-semibold">Portfolio</h1>
      <div className="flex flex-col items-end gap-3">
        <div className="text-right text-sm text-muted-foreground">
          {sync ? <div>Last sync: {sync}</div> : <div>Last sync: —</div>}
          {data?.collectionWarning && (
            <div className="mt-1 text-amber-700">⚠ {data.collectionWarning.message}</div>
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
        <PeriodSelector period={period} onPeriod={onPeriod} />
      </div>
    </div>
  );
}

function KpiCard({ label, value }: { label: string; value: number }) {
  return (
    <Card>
      <div className="text-sm text-muted-foreground">{label}</div>
      <div className="mt-2 text-2xl font-semibold">{formatNumber(value)}</div>
    </Card>
  );
}

function TrafficKpi({
  label,
  metric,
  period,
}: {
  label: string;
  metric: { value: number; growthPercent: number | null };
  period: Period;
}) {
  const growth = formatGrowth(metric.growthPercent);
  return (
    <Card>
      <div className="text-sm text-muted-foreground">{label}</div>
      <div className="mt-2 text-2xl font-semibold">{formatNumber(metric.value)}</div>
      {period !== "all" && (
        <div className={cn("mt-2 text-sm font-medium", growth ? growthClass(growth.direction) : "text-muted-foreground")}>
          {growth?.label ?? "—"}
          {growth && <div className="text-xs font-normal text-muted-foreground">vs prev {period}</div>}
        </div>
      )}
    </Card>
  );
}

function StarsKpi({ stars, period }: { stars: { total: number; change: number | null }; period: Period }) {
  const change = stars.change;
  return (
    <Card>
      <div className="text-sm text-muted-foreground">Stars</div>
      <div className="mt-2 text-2xl font-semibold">
        {formatNumber(stars.total)} <span className="text-sm font-normal text-muted-foreground">total</span>
      </div>
      {period !== "all" && (
        <div className={cn("mt-2 text-sm font-medium", (change ?? 0) > 0 ? "text-emerald-700" : (change ?? 0) < 0 ? "text-red-700" : "text-muted-foreground")}>
          {change !== null && change !== undefined ? `${change > 0 ? "+" : ""}${formatNumber(change)} in ${period}` : "—"}
        </div>
      )}
    </Card>
  );
}

function TrafficChart({ traffic }: { traffic: Dashboard["traffic"] }) {
  const option = useMemo(
    () => ({
      tooltip: {
        trigger: "axis",
        formatter: (params: { axisValue: string; seriesName: string; data: number | null }[]) => {
          if (!Array.isArray(params) || params.length === 0) {
            return "";
          }
          const date = new Intl.DateTimeFormat("en-US", { month: "short", day: "numeric" }).format(
            new Date(`${params[0].axisValue}T00:00:00`),
          );
          const rows = params
            .map((item) => {
              const value = item.data === null || item.data === undefined ? "—" : formatNumber(item.data);
              return `<div style="display:flex;justify-content:space-between;gap:24px"><span>${item.seriesName}</span><span>${value}</span></div>`;
            })
            .join("");
          return `<div style="min-width:140px"><div style="margin-bottom:6px">${date}</div>${rows}</div>`;
        },
      },
      legend: { data: ["Views", "Visitors", "Clones"] },
      grid: { left: 16, right: 16, top: 40, bottom: 24, containLabel: true },
      xAxis: {
        type: "category",
        data: traffic.map((point) => point.date),
        boundaryGap: false,
      },
      yAxis: { type: "value", name: "Metric value" },
      series: [
        { name: "Views", type: "line", connectNulls: false, data: traffic.map((point) => point.views ?? null) },
        { name: "Visitors", type: "line", connectNulls: false, data: traffic.map((point) => point.visitors ?? null) },
        { name: "Clones", type: "line", connectNulls: false, data: traffic.map((point) => point.clones ?? null) },
      ],
    }),
    [traffic],
  );

  return (
    <Card>
      <h2 className="mb-3 font-medium">Traffic</h2>
      <ReactECharts option={option} style={{ height: 360, width: "100%" }} opts={{ renderer: "canvas" }} />
    </Card>
  );
}

function RepositoryTable({ rows }: { rows: Dashboard["repositories"] }) {
  const navigate = useNavigate();
  const [sortKey, setSortKey] = useState<SortKey>("visitors");
  const [sortDir, setSortDir] = useState<"asc" | "desc">("desc");

  function toggle(key: SortKey) {
    if (sortKey === key) {
      setSortDir((current) => (current === "desc" ? "asc" : "desc"));
      return;
    }
    setSortKey(key);
    setSortDir("desc");
  }

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
        <table className="w-full min-w-[860px] text-sm">
          <thead className="text-left text-muted-foreground">
            <tr>
              <th className="px-5 py-2 font-medium">Repository</th>
              <SortHeader label="Visitors" active={sortKey === "visitors"} dir={sortDir} onClick={() => toggle("visitors")} />
              <SortHeader label="Views" active={sortKey === "views"} dir={sortDir} onClick={() => toggle("views")} />
              <SortHeader label="Clones" active={sortKey === "clones"} dir={sortDir} onClick={() => toggle("clones")} />
              <SortHeader label="Stars" active={sortKey === "stars"} dir={sortDir} onClick={() => toggle("stars")} />
              <SortHeader label="Growth" active={sortKey === "growth"} dir={sortDir} onClick={() => toggle("growth")} />
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
                  <td className="px-5 py-3">
                    <Link
                      className="text-primary hover:underline"
                      to={`/repositories/${row.id}`}
                      onClick={(event) => event.stopPropagation()}
                    >
                      {row.fullName}
                    </Link>
                    {row.collectionStatus === "PARTIAL" && (
                      <CollectionHint jobs={row.jobs} />
                    )}
                  </td>
                  <td className="px-5 text-right tabular-nums">{formatNumber(row.visitors)}</td>
                  <td className="px-5 text-right tabular-nums">{formatNumber(row.views)}</td>
                  <td className="px-5 text-right tabular-nums">{formatNumber(row.clones)}</td>
                  <td className="px-5 text-right tabular-nums">{formatNumber(row.stars)}</td>
                  <td className={cn("px-5 text-right tabular-nums font-medium", growthClass(growth?.direction))}>
                    {growth?.label ?? "—"}
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

function FirstCollectionState({
  data,
  period,
  onPeriod,
}: {
  data: Dashboard;
  period: Period;
  onPeriod: (period: Period) => void;
}) {
  return (
    <div className="space-y-6">
      <DashboardHeader data={data} period={period} onPeriod={onPeriod} />
      <Card className="py-12">
        <h2 className="text-lg font-medium">Collecting your first data...</h2>
        <p className="mt-2 max-w-lg text-sm text-muted-foreground">
          Your dashboard will appear after the first GitHub collection completes.
        </p>
        {data.activeCollection && (
          <p className="mt-4 text-sm text-muted-foreground">
            {data.activeCollection.status} · {data.activeCollection.successfulJobs} / {data.activeCollection.plannedJobs} jobs
          </p>
        )}
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
