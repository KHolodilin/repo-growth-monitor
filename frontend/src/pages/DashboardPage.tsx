import { RepositoryWarningSheet } from "../components/RepositoryWarningSheet";
import { useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { api, type Dashboard as ApiDashboard, type GrowthEvent, type RankHighlight } from "../lib/api";
import { dashboardTrafficOption } from "../lib/dashboardChart";
import { statsChartOption } from "../lib/statsChart";
import { asGrowthEvents } from "../lib/growthEvents";
import { activityClass, cn, formatActivityPresentation, formatGrowth, formatNumber, growthClass } from "../lib/utils";
import { RepositoryAccessNote } from "../components/RepositoryAccessNote";
import { Button, Card, Skeleton } from "../components/ui";
import { loadGitHubAccess, parseGitHubAccess, type GitHubAccess, type TokenState } from "../lib/githubAccess";
import { checklistSchedule, collectionJobLines, DEFAULT_PLANNER } from "../lib/setupChecklist";
import { repositoryProblemBadge, repositoryProblemLines } from "../lib/trafficGap";
import { PeriodSelector, usePeriod, type Period } from "../components/PeriodSelector";
import { TrafficKpiCard } from "../components/TrafficKpiCard";
import { ChartCard } from "../components/ChartDownloadMenu";
import { PersistentECharts } from "../components/PersistentECharts";
import { dashboardStatsChartId, dashboardTrafficChartId, DASHBOARD_STATS_SERIES, TRAFFIC_SERIES } from "../lib/chartLegend";
import { useTableSort } from "../lib/tableSortPrefs";
import { useIsMobileChart } from "../lib/useMediaQuery";

import { readPublicPreference, writePublicPreference } from "../lib/publicPrefs";
import { useDashboardView, repositoryHref } from "../lib/dashboardView";
type Dashboard = Omit<ApiDashboard, "repositories" | "summary"> & { summary: ApiDashboard["summary"] & { clones?: ApiDashboard["summary"]["views"] }; repositories: (Omit<ApiDashboard["repositories"][number], "id" | "stars" | "forks"> & { id: number | string; stars: number | null; forks: number | null })[] };
export type DashboardViewData = Dashboard;

const SORT_KEYS = ["visitors", "views", "clones", "stars", "forks", "growth", "discoverability"] as const;

type SortKey = (typeof SORT_KEYS)[number];

const SORT_LABELS: Record<SortKey, string> = {
  visitors: "Visitors",
  views: "Views",
  clones: "Clones",
  stars: "Stars",
  forks: "Forks",
  growth: "Growth",
  discoverability: "Disc.",
};

const PORTFOLIO_STATS = [
  { key: "stars", label: "Stars" },
  { key: "forks", label: "Forks" },
  { key: "watchers", label: "Watchers" },
  { key: "contributors", label: "Unique Contributors" },
] as const;

export function DashboardPage() {
  const [trafficSeries,setTrafficSeries]=useState("Visitors");
  const [statsSeries,setStatsSeries]=useState("Stars");
  const [searchParams, setSearchParams] = useSearchParams();
  const tab = searchParams.get("tab") === "stats" ? "stats" : "traffic";
  const [period, setPeriod] = usePeriod();
  const requestVersion = useRef(0);
  const [data, setData] = useState<Dashboard | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const access = useGitHubAccess();

  async function load() {
    const version = ++requestVersion.current;
    const dashboard = await api<Dashboard>(`/api/v1/admin/dashboard?period=${period}`);
    if (version === requestVersion.current) setData(dashboard);
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
      requestVersion.current++;
    };
  }, [period]);

  const trackedIds = data?.repositories.map((row) => Number(row.id)) ?? [];
  const portfolioEvents = useTrackedEvents(trackedIds, period);
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

  function setTab(next: "traffic" | "stats") {
    const params = new URLSearchParams(searchParams);
    if (next === "stats") {
      params.set("tab", "stats");
    } else {
      params.delete("tab");
    }
    setSearchParams(params, { replace: true });
  }

  if (error) {
    return <p className="text-red-600">{error}</p>;
  }
  if (loading || !data) {
    return <DashboardSkeleton period={period} onPeriod={setPeriod} tab={tab} onTab={setTab} />;
  }
  if (data.state === "NO_REPOSITORIES") {
    return <SetupChecklist period={period} onPeriod={setPeriod} access={access} />;
  }

  return <DashboardPresentation data={data} period={period} onPeriod={setPeriod} tab={tab} onTab={setTab} events={portfolioEvents} mobileSelection={{traffic:trafficSeries,stats:statsSeries,onTraffic:setTrafficSeries,onStats:setStatsSeries}} />;
}

export function DashboardPresentation({ data, period, onPeriod, tab, onTab, events = [], mobileSelection }: { data: Dashboard; period: Period; onPeriod: (period: Period) => void; tab: "traffic" | "stats"; onTab: (tab: "traffic" | "stats") => void; events?: GrowthEvent[]; mobileSelection?: {traffic:string;stats:string;onTraffic:(name:string)=>void;onStats:(name:string)=>void} }) {
  const view = useDashboardView();
  const [trafficSeries, changeTrafficSeries] = useState(() => view.public ? readPublicPreference(view.scopeKey!, "mobile:traffic", (v):v is string => typeof v === "string") ?? "Visitors" : "Visitors");
  const [statsSeries, changeStatsSeries] = useState(() => view.public ? readPublicPreference(view.scopeKey!, "mobile:stats", (v):v is string => typeof v === "string") ?? "Stars" : "Stars");
  const setTrafficSeries=(next:string)=>{changeTrafficSeries(next);mobileSelection?.onTraffic(next);if(view.public)writePublicPreference(view.scopeKey!,"mobile:traffic",next);};
  const setStatsSeries=(next:string)=>{changeStatsSeries(next);mobileSelection?.onStats(next);if(view.public)writePublicPreference(view.scopeKey!,"mobile:stats",next);};
  return (
    <div className="space-y-4">
      <DashboardHeader data={data} period={period} onPeriod={onPeriod} tab={tab} onTab={onTab} />
      {tab === "traffic" ? (
        <div className="grid auto-rows-fr grid-cols-2 gap-3 lg:grid-cols-4">
          {view.showRepositories === false && data.summary.clones ? <TrafficKpiCard label="Clones" value={data.summary.clones.value} growthPercent={data.summary.clones.growthPercent} coverage={data.summary.clones.coverage} period={period} series={data.traffic.map(point=>point.clones)}/> : <KpiCard label="Repositories" value={data.summary.repositories} />}
          <TrafficKpiCard
            label="Views"
            coverage={data.summary.views.coverage}
            previousCoverage={data.summary.views.previousCoverage}
            value={data.summary.views.value}
            growthPercent={data.summary.views.growthPercent}
            period={period}
            series={data.traffic.map((point) => point.views)}
          />
          <TrafficKpiCard
            label="Visitors"
            coverage={data.summary.visitors.coverage}
            previousCoverage={data.summary.visitors.previousCoverage}
            value={data.summary.visitors.value}
            growthPercent={data.summary.visitors.growthPercent}
            period={period}
            series={data.traffic.map((point) => point.visitors)}
          />
          <TrafficKpiCard
            label="Unique Cloners"
            coverage={data.summary.uniqueCloners.coverage}
            previousCoverage={data.summary.uniqueCloners.previousCoverage}
            value={data.summary.uniqueCloners.value}
            growthPercent={data.summary.uniqueCloners.growthPercent}
            period={period}
            series={data.traffic.map((point) => point.uniqueCloners ?? null)}
          />
        </div>
      ) : (
        <div className="grid auto-rows-fr grid-cols-2 gap-3 lg:grid-cols-4">
          {PORTFOLIO_STATS.map((card) => {
            const total = data.stats.totals[card.key];
            return (
              <TrafficKpiCard
                key={card.key}
                label={card.label}
                value={total.value}
                growthPercent={total.growthPercent}
                absoluteChange={total.absoluteChange}
                period={period}
                series={data.stats.points.map((point) => point[card.key])}

              />
            );
          })}
        </div>
      )}
      {data.state === "FIRST_COLLECTION" ? (
        <CollectingCard rows={data.repositories} />
      ) : tab === "traffic" ? (
        <TrafficChart traffic={data.traffic} events={events} period={period} selectedMobileSeries={mobileSelection?.traffic ?? trafficSeries} onMobileSeriesChange={setTrafficSeries} />
      ) : (
        <StatsChart points={data.stats.points} events={events} period={period} selectedMobileSeries={mobileSelection?.stats ?? statsSeries} onMobileSeriesChange={setStatsSeries} />
      )}
      <PeriodHighlights highlights={data.highlights} />
      {view.showRepositories !== false && <RepositoryTable rows={data.repositories} />}
    </div>
  );
}

function DashboardHeader({
  data,
  period,
  onPeriod,
  tab,
  onTab,
}: {
  data?: Dashboard;
  period: Period;
  onPeriod: (period: Period) => void;
  tab?: "traffic" | "stats";
  onTab?: (tab: "traffic" | "stats") => void;
}) {
  const view = useDashboardView();
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);
  const mobile = useIsMobileChart();
  const problems = (data?.repositories ?? [])
    .map((row) => ({ row, lines: repositoryProblemLines(row) }))
    .filter((item) => item.lines.length > 0);
  useEffect(() => {
    if (!open || mobile) {
      return;
    }
    function onPointerDown(event: MouseEvent) {
      if (!rootRef.current?.contains(event.target as Node)) {
        setOpen(false);
      }
    }
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") {
        setOpen(false);
      }
    }
    document.addEventListener("mousedown", onPointerDown);
    document.addEventListener("keydown", onKeyDown);
    return () => {
      document.removeEventListener("mousedown", onPointerDown);
      document.removeEventListener("keydown", onKeyDown);
    };
  }, [open, mobile]);
  const warningList = problems.map(({ row, lines }) => <div key={row.id} className="border-b py-3 text-sm last:border-0"><Link className="inline-block min-h-11 max-w-full break-words py-2 text-primary hover:underline [overflow-wrap:anywhere]" to={repositoryHref(view, row.id)} onClick={() => setOpen(false)}>{row.fullName}</Link>{lines.map(line => <div key={line} className="break-words text-muted-foreground">{line}</div>)}</div>);
  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-3">
        <h1 className="min-w-0 break-words text-2xl font-semibold [overflow-wrap:anywhere]">{view.heading ?? "Portfolio"}</h1>
        {problems.length > 0 && (
          <div ref={rootRef} className="relative">
            <button
              type="button"
              className="text-sm text-amber-700"
              aria-expanded={open}
              onClick={() => setOpen((current) => !current)}
            >
              ⚠ {repositoryProblemBadge(problems.length)}
            </button>
            {open && mobile && <RepositoryWarningSheet count={problems.length} onClose={() => setOpen(false)}>{warningList}</RepositoryWarningSheet>}
            {open && !mobile && (
              <div className="absolute left-0 z-10 mt-2 w-80 max-w-[calc(100vw-2rem)] rounded-md border bg-card p-3 text-left shadow-lg">
                {problems.map(({ row, lines }) => (
                  <div key={row.id} className="py-1 text-sm">
                    <Link className="text-primary hover:underline" to={repositoryHref(view, row.id)}>
                      {row.fullName}
                    </Link>
                    {lines.map((line) => (
                      <div key={line} className="text-muted-foreground">{line}</div>
                    ))}
                  </div>
                ))}
              </div>
            )}
          </div>
        )}
      </div>
      <div
        className={cn(
          "flex gap-3",
          tab != null && onTab != null
            ? "flex-col md:flex-row md:items-center md:justify-end md:gap-2"
            : null,
        )}
        data-testid="portfolio-controls"
      >
        {!view.public && tab != null && onTab != null && (
          <div className="inline-flex rounded-lg border bg-muted p-1" role="group" aria-label="Portfolio analytics">
            {([
              ["traffic", "Traffic"],
              ["stats", "Stats"],
            ] as const).map(([key, label]) => (
              <button
                hidden={view.public && key === "traffic" && view.trafficEnabled === false}
                key={key}
                type="button"
                className={cn(
                  "rounded-md px-3 py-1.5 text-sm font-medium",
                  tab === key ? "bg-card text-foreground shadow-xs" : "text-muted-foreground hover:text-foreground",
                )}
                onClick={() => onTab(key)}
              >
                {label}
              </button>
            ))}
          </div>
        )}
        <div
          className="overflow-x-auto"
          aria-label={view.public ? `${view.heading} period` : "Portfolio period"}
        >
          <div className="inline-flex min-w-min flex-nowrap rounded-lg border bg-muted p-1">
            <PeriodSelector embedded scrollable period={period} onPeriod={onPeriod} />
          </div>
        </div>
      </div>
    </div>
  );
}

function useTrackedEvents(repositoryIds: number[], period: string) {
  const key = repositoryIds.join(",");
  const [events, setEvents] = useState<GrowthEvent[]>([]);
  useEffect(() => {
    const ids = key ? key.split(",").map(Number) : [];
    if (ids.length === 0) {
      setEvents([]);
      return;
    }
    let cancelled = false;
    Promise.all(
      ids.map((id) =>
        api<GrowthEvent[]>(`/api/v1/admin/repositories/${id}/growth-events?period=${period}`)
          .then((rows) => asGrowthEvents(rows))
          .catch(() => [] as GrowthEvent[]),
      ),
    ).then((groups) => {
      if (!cancelled) {
        setEvents(groups.flat());
      }
    });
    return () => {
      cancelled = true;
    };
  }, [key, period]);
  return events;
}

function KpiCard({ label, value }: { label: string; value: number }) {
  return (
    <Card className="flex h-full flex-col p-3">
      <div className="text-sm text-muted-foreground">{label}</div>
      <div className="mt-1 text-2xl font-semibold">{formatNumber(value)}</div>
    </Card>
  );
}

function TrafficChart({
  traffic,
  events,
  period,
  selectedMobileSeries,
  onMobileSeriesChange,
}: {
  traffic: Dashboard["traffic"];
  events: GrowthEvent[];
  period: Period;
  selectedMobileSeries: string;
  onMobileSeriesChange: (name: string) => void;
}) {
  const view = useDashboardView();
  const option = useMemo(() => dashboardTrafficOption(traffic), [traffic]);

  return (
    <ChartCard
      title="Traffic"
      events={events}
      period={period}
      context={view.scopeKey ? `${view.scopeKey}:traffic` : "portfolio:traffic"}
      selectedMobileSeries={selectedMobileSeries}
      onMobileSeriesChange={onMobileSeriesChange}
      mobileSeries={TRAFFIC_SERIES}
    >
      {!traffic.some(point => point.views != null || point.visitors != null || point.clones != null || point.uniqueCloners != null) ? <p role="status" className="py-12 text-center text-sm text-muted-foreground">No traffic data for selected period</p> : <PersistentECharts
        accessibleTitle={`${view.heading ?? "Portfolio"} Traffic`}
        chartId={view.scopeKey ? `${view.scopeKey}:traffic` : dashboardTrafficChartId()}
        series={TRAFFIC_SERIES}
        option={option}
        style={{ height: 360, width: "100%" }}
        opts={{ renderer: "canvas" }}
      />}
    </ChartCard>
  );
}

function StatsChart({
  points,
  events,
  period,
  selectedMobileSeries,
  onMobileSeriesChange,
}: {
  points: Dashboard["stats"]["points"];
  events: GrowthEvent[];
  period: Period;
  selectedMobileSeries: string;
  onMobileSeriesChange: (name: string) => void;
}) {
  const view = useDashboardView();
  const option = useMemo(
    () => statsChartOption(points, { contributorsLabel: "Unique Contributors" }),
    [points],
  );
  return (
    <ChartCard
      title="Stats"
      events={events}
      period={period}
      context={view.scopeKey ? `${view.scopeKey}:stats` : "portfolio:stats"}
      selectedMobileSeries={selectedMobileSeries}
      onMobileSeriesChange={onMobileSeriesChange}
      mobileSeries={DASHBOARD_STATS_SERIES}
    >
      <PersistentECharts
        accessibleTitle={`${view.heading ?? "Portfolio"} Stats`}
        chartId={view.scopeKey ? `${view.scopeKey}:stats` : dashboardStatsChartId()}
        series={DASHBOARD_STATS_SERIES}
        option={option}
        style={{ height: 360, width: "100%" }}
      />
    </ChartCard>
  );
}

function PeriodHighlights({ highlights }: { highlights: Dashboard["highlights"] }) {
  const view = useDashboardView();
  const rise = highlights?.rankRise;
  const drop = highlights?.rankDrop;
  const events = highlights?.events;
  const visitors = highlights?.visitors;
  const growth = visitors ? formatGrowth(visitors.growthPercent) : null;
  return (
    <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
      <HighlightCard label="Rank">
        {rise && <RankLink move={rise} />}
        {drop && <RankLink move={drop} />}
        {!rise && !drop && <span className="text-sm text-muted-foreground">No rank changes</span>}
      </HighlightCard>
      <HighlightCard label="Events">
        {events ? (
          <Link
            className="block text-primary hover:underline"
            to={`${repositoryHref(view, events.repositoryId)}?tab=${view.public ? "events" : "growth-events"}`}
            aria-label={`${events.count} ${events.count === 1 ? "promotion event" : "promotion events"} · ${events.repositoryNames.join(", ")}`}
          >
            <div className="text-base font-semibold text-foreground">
              {events.count} {events.count === 1 ? "promotion event" : "promotion events"}
            </div>
            <div className="mt-1 line-clamp-2 text-sm text-muted-foreground">{events.repositoryNames.join(", ")}</div>
          </Link>
        ) : (
          <span className="text-sm text-muted-foreground">No promotion events</span>
        )}
      </HighlightCard>
      <HighlightCard label="Visitors">
        {visitors && growth ? (
          <Link
            className="block hover:underline"
            to={repositoryHref(view, visitors.repositoryId)}
            aria-label={`${visitors.fullName} ${growth.label}`}
          >
            <div className={cn("text-base font-semibold", growthClass(growth.direction))}>{growth.label}</div>
            <div className="mt-1 line-clamp-2 text-sm text-muted-foreground">{visitors.fullName}</div>
          </Link>
        ) : (
          <span className="text-sm text-muted-foreground">No visitor growth</span>
        )}
      </HighlightCard>
    </div>
  );
}

function HighlightCard({ label, children }: { label: string; children: ReactNode }) {
  return (
    <Card className="flex h-full flex-col gap-2 p-3">
      <div className="text-sm text-muted-foreground">{label}</div>
      <div className="flex flex-col gap-2">{children}</div>
    </Card>
  );
}

function RankLink({ move }: { move: RankHighlight }) {
  const view = useDashboardView();
  return (
    <Link
      className="block text-primary hover:underline"
      to={`${repositoryHref(view, move.repositoryId)}?tab=search`}
      aria-label={`${move.fullName} · ${move.queryName} ${move.fromRank} → ${move.toRank}`}
    >
      <div className="text-base font-semibold text-foreground">
        {move.fromRank} → {move.toRank}
      </div>
      <div className="mt-1 line-clamp-2 text-sm text-muted-foreground">
        {move.fullName} · {move.queryName}
      </div>
    </Link>
  );
}

function RepositoryTable({ rows }: { rows: Dashboard["repositories"] }) {
  const view = useDashboardView();
  const mobile = useIsMobileChart();
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
    scope: view.scopeKey,
    keys: SORT_KEYS,
    initial: { key: "visitors", dir: "desc" },
  });
  const activeSortKey = sortKey as SortKey;

  const sorted = useMemo(() => {
    const copy = [...rows];
    copy.sort((left, right) => {
      const leftValue = sortValue(left, activeSortKey);
      const rightValue = sortValue(right, activeSortKey);
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
  }, [rows, activeSortKey, sortDir]);

  if (mobile) {
    return (
      <div className="space-y-3">
        <div className="flex flex-wrap items-center gap-2">
          <label className="flex min-w-0 flex-1 items-center gap-2 text-sm">
            <span className="shrink-0 text-muted-foreground">Sort</span>
            <select
              className="min-w-0 flex-1 rounded-md border bg-card px-3 py-2"
              aria-label="Sort repositories by"
              value={activeSortKey}
              onChange={(event) => toggle(event.target.value as SortKey)}
            >
              {SORT_KEYS.map((key) => (
                <option key={key} value={key}>
                  {SORT_LABELS[key]}
                </option>
              ))}
            </select>
          </label>
          <button
            type="button"
            className="rounded-md border bg-card px-3 py-2 text-sm font-medium"
            aria-label={sortDir === "desc" ? "Sort descending" : "Sort ascending"}
            onClick={() => toggle(activeSortKey)}
          >
            {sortDir === "desc" ? "↓" : "↑"}
          </button>
        </div>
        <ul className="space-y-3">
          {sorted.map((row) => (
            <li key={row.id}>
              <RepositoryCard
                row={row}
                tokenState={tokenState}
                onOpen={() => navigate(repositoryHref(view, row.id))}
              />
            </li>
          ))}
        </ul>
      </div>
    );
  }

  return (
    <Card className="overflow-hidden p-0">
      <div className="overflow-x-auto pt-3">
        <table className="w-full min-w-[1000px] text-sm">
          <thead className="text-left text-muted-foreground">
            <tr>
              <th className="px-5 py-2 font-medium">Repository</th>
              <SortHeader label="Visitors" active={activeSortKey === "visitors"} dir={sortDir} onClick={() => toggle("visitors")} />
              <SortHeader label="Views" active={activeSortKey === "views"} dir={sortDir} onClick={() => toggle("views")} />
              <SortHeader label="Clones" active={activeSortKey === "clones"} dir={sortDir} onClick={() => toggle("clones")} />
              <SortHeader label="Stars" active={activeSortKey === "stars"} dir={sortDir} onClick={() => toggle("stars")} />
              <SortHeader label="Forks" active={activeSortKey === "forks"} dir={sortDir} onClick={() => toggle("forks")} />
              <SortHeader label="Growth" active={activeSortKey === "growth"} dir={sortDir} onClick={() => toggle("growth")} />
              <SortHeader label="Disc." active={activeSortKey === "discoverability"} dir={sortDir} onClick={() => toggle("discoverability")} />
              <th className="px-5 py-2 font-medium">Activity</th>
            </tr>
          </thead>
          <tbody>
            {sorted.map((row) => {
              const growth = formatGrowth(row.growthPercent);
              const problems = repositoryProblemLines(row).join(" ");
              return (
                <tr
                  key={row.id}
                  className="cursor-pointer border-t hover:bg-muted/60"
                  onClick={() => navigate(repositoryHref(view, row.id))}
                >
                  <td className="max-w-0 px-5 py-3 align-top">
                    {problems && (
                      <span role="img" className="mr-1 text-amber-700" title={problems} aria-label={problems}>
                        !
                      </span>
                    )}
                    <Link
                      className="break-words [overflow-wrap:anywhere] text-primary hover:underline"
                      to={repositoryHref(view, row.id)}
                      onClick={(event) => event.stopPropagation()}
                    >
                      {row.fullName}
                    </Link>
                    <RepositoryAccessNote
                      accessible={row.accessible}
                      trafficPermissionMissing={row.trafficPermissionMissing}
                      tokenState={tokenState}
                    />
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

function RepositoryCard({
  row,
  tokenState,
  onOpen,
}: {
  row: Dashboard["repositories"][number];
  tokenState: TokenState | null;
  onOpen: () => void;
}) {
  const view = useDashboardView();
  const growth = formatGrowth(row.growthPercent);
  const problems = repositoryProblemLines(row).join(" ");
  const metrics: { label: string; value: ReactNode }[] = [
    { label: "Visitors", value: formatNumber(row.visitors) },
    { label: "Views", value: formatNumber(row.views) },
    { label: "Clones", value: formatNumber(row.clones) },
    { label: "Stars", value: formatNumber(row.stars) },
    { label: "Forks", value: formatNumber(row.forks) },
    {
      label: "Growth",
      value: <span className={cn("font-medium", growthClass(growth?.direction))}>{growth?.label ?? "—"}</span>,
    },
    { label: "Disc.", value: <DiscoverabilityCell row={row} /> },
    {
      label: "Activity",
      value: <span className={activityClass(row.activityStatus)}>{formatActivityPresentation(row.activityStatus, row.activityAt)}</span>,
    },
  ];
  return (
    <Card className="cursor-pointer p-4 hover:bg-muted/40" onClick={onOpen}>
      <div className="flex items-start gap-2">
        {problems ? (
          <span role="img" className="mt-0.5 shrink-0 text-amber-700" title={problems} aria-label={problems}>
            !
          </span>
        ) : null}
        <div className="min-w-0 flex-1">
          <Link
            className="break-words text-base font-medium text-primary [overflow-wrap:anywhere] hover:underline"
            to={repositoryHref(view, row.id)}
          >
            {row.fullName}
          </Link>
          <RepositoryAccessNote
            accessible={row.accessible}
            trafficPermissionMissing={row.trafficPermissionMissing}
            tokenState={tokenState}
          />
        </div>
      </div>
      <dl className="mt-3 grid grid-cols-2 gap-x-4 gap-y-2 text-sm">
        {metrics.map((metric) => (
          <div key={metric.label} className="min-w-0">
            <dt className="text-muted-foreground">{metric.label}</dt>
            <dd className="mt-0.5 break-words tabular-nums [overflow-wrap:anywhere]">{metric.value}</dd>
          </div>
        ))}
      </dl>
    </Card>
  );
}

function DiscoverabilityCell({ row }: { row: Dashboard["repositories"][number] }) {
  const view = useDashboardView();
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
      to={`${repositoryHref(view, row.id)}?tab=overview&focus=health`}
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

function sortValue(row: Dashboard["repositories"][number], key: SortKey): number | null {
  if (key === "growth") {
    return row.growthPercent;
  }
  if (key === "discoverability") {
    return row.discoverabilityPassed;
  }
  return row[key];
}

function useGitHubAccess(): GitHubAccess | null {
  const [access, setAccess] = useState<GitHubAccess | null>(null);
  useEffect(() => {
    let cancelled = false;
    async function load() {
      try {
        const next = parseGitHubAccess(await api<unknown>("/api/v1/admin/system/status"));
        if (!cancelled) {
          setAccess(next);
        }
      } catch {
        if (!cancelled) {
          setAccess(null);
        }
      }
    }
    void load();
    const timer = window.setInterval(() => void load(), 15000);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, []);
  return access;
}

function tokenAccepted(access: GitHubAccess | null): boolean {
  return access?.tokenState === "OK" || access?.tokenState === "RATE_LIMITED";
}

function SetupChecklist({
  period,
  onPeriod,
  access,
}: {
  period: Period;
  onPeriod: (period: Period) => void;
  access: GitHubAccess | null;
}) {
  const accepted = tokenAccepted(access);
  const planner = access?.planner ?? DEFAULT_PLANNER;
  return (
    <div className="space-y-6">
      <DashboardHeader period={period} onPeriod={onPeriod} />
      <Card className="space-y-5">
        <ChecklistRow done title="Application is running." />
        <ChecklistRow done={accepted} title="GitHub accepted the token.">
          {!accepted && <TokenHelp rejected={access?.tokenState === "REJECTED"} />}
        </ChecklistRow>
        <ChecklistRow done={false} title="Repositories are selected." muted={!accepted}>
          {accepted && (
            <Link to="/admin/repositories" className="mt-2 inline-block">
              <Button>Select repositories</Button>
            </Link>
          )}
        </ChecklistRow>
        <p className="text-sm text-muted-foreground">{checklistSchedule(planner)}</p>
      </Card>
    </div>
  );
}

function ChecklistRow({
  done,
  title,
  muted,
  children,
}: {
  done: boolean;
  title: string;
  muted?: boolean;
  children?: ReactNode;
}) {
  return (
    <div>
      <div className={cn("text-sm font-medium", done ? "text-emerald-700" : muted ? "text-muted-foreground" : "text-foreground")}>
        <span className="mr-2" aria-hidden="true">
          {done ? "✓" : "○"}
        </span>
        {title}
      </div>
      {children}
    </div>
  );
}

function TokenHelp({ rejected }: { rejected: boolean }) {
  return (
    <div className="mt-2 max-w-xl space-y-2 text-sm text-muted-foreground">
      {rejected && (
        <p>GitHub rejected the token. Stored data is kept. Update GITHUB_TOKEN in .env and restart.</p>
      )}
      <p>
        <a
          className="text-primary hover:underline"
          href="https://github.com/settings/personal-access-tokens/new"
          target="_blank"
          rel="noreferrer"
        >
          Create a fine-grained token
        </a>
      </p>
      <p>Read permissions: Metadata, Contents, Administration, Issues, Pull requests.</p>
      <p>Without Administration, traffic stays empty and the rest still collects.</p>
      <p>
        Write the token to <code className="text-foreground">.env</code> as GITHUB_TOKEN, then run{" "}
        <code className="text-foreground">docker compose up -d</code>. There is no token field in the browser.
      </p>
    </div>
  );
}

function CollectingCard({ rows }: { rows: Dashboard["repositories"] }) {
  return (
    <Card>
      <h2 className="font-medium">Collecting</h2>
      <div className="mt-4 space-y-4">
        {rows.map((row) => (
          <div key={row.id}>
            <div className="text-sm font-medium">{row.fullName}</div>
            <ul className="mt-1 space-y-1 text-sm">
              {collectionJobLines(row.jobs, row.trafficPermissionMissing === true).map((line) => (
                <li key={line.jobType}>
                  <span>
                    {line.mark} {line.label}
                  </span>
                  {line.detail && <div className="ml-5 text-red-700">{line.detail}</div>}
                </li>
              ))}
            </ul>
          </div>
        ))}
      </div>
    </Card>
  );
}

function DashboardSkeleton({
  period,
  onPeriod,
  tab,
  onTab,
}: {
  period: Period;
  onPeriod: (period: Period) => void;
  tab: "traffic" | "stats";
  onTab: (tab: "traffic" | "stats") => void;
}) {
  return (
    <div className="space-y-6">
      <DashboardHeader period={period} onPeriod={onPeriod} tab={tab} onTab={onTab} />
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        {Array.from({ length: 4 }).map((_, index) => (
          <Skeleton key={index} className="h-28" />
        ))}
      </div>
      <Skeleton className="h-96" />
      <Skeleton className="h-64" />
    </div>
  );
}
