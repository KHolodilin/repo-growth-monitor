import { useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { api, type Dashboard, type GrowthEvent, type RankHighlight } from "../lib/api";
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
import { dashboardStatsChartId, dashboardTrafficChartId, STATS_SERIES, TRAFFIC_SERIES } from "../lib/chartLegend";
import { useTableSort } from "../lib/tableSortPrefs";

const SORT_KEYS = ["visitors", "views", "clones", "stars", "forks", "growth", "discoverability"] as const;

type SortKey = (typeof SORT_KEYS)[number];

const PORTFOLIO_STATS = [
  { key: "stars", label: "Stars", color: "#5470c6" },
  { key: "forks", label: "Forks", color: "#91cc75" },
  { key: "watchers", label: "Watchers", color: "#fac858" },
  { key: "contributors", label: "Contributors", color: "#ee6666" },
] as const;

export function DashboardPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const tab = searchParams.get("tab") === "stats" ? "stats" : "traffic";
  const [period, setPeriod] = usePeriod();
  const [data, setData] = useState<Dashboard | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const access = useGitHubAccess();

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

  const trackedIds = data?.repositories.map((row) => row.id) ?? [];
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

  return (
    <div className="space-y-4">
      <DashboardHeader data={data} period={period} onPeriod={setPeriod} tab={tab} onTab={setTab} />
      {tab === "traffic" ? (
        <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
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
        </div>
      ) : (
        <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
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
                sparklineColor={card.color}
              />
            );
          })}
        </div>
      )}
      {data.state === "FIRST_COLLECTION" ? (
        <CollectingCard rows={data.repositories} />
      ) : tab === "traffic" ? (
        <TrafficChart traffic={data.traffic} events={portfolioEvents} period={period} />
      ) : (
        <StatsChart points={data.stats.points} events={portfolioEvents} period={period} />
      )}
      <PeriodHighlights highlights={data.highlights} />
      <RepositoryTable rows={data.repositories} />
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
  tab: "traffic" | "stats";
  onTab: (tab: "traffic" | "stats") => void;
}) {
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);
  const problems = (data?.repositories ?? [])
    .map((row) => ({ row, lines: repositoryProblemLines(row) }))
    .filter((item) => item.lines.length > 0);
  useEffect(() => {
    if (!open) {
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
  }, [open]);
  return (
    <div className="flex flex-wrap items-center justify-between gap-3">
      <div className="flex flex-wrap items-center gap-3">
        <h1 className="text-2xl font-semibold">Portfolio</h1>
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
            {open && (
              <div className="absolute left-0 z-10 mt-2 w-80 rounded-md border bg-card p-3 text-left shadow-lg">
                {problems.map(({ row, lines }) => (
                  <div key={row.id} className="py-1 text-sm">
                    <Link className="text-primary hover:underline" to={`/repositories/${row.id}`}>
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
      <div className="inline-flex max-w-full flex-wrap rounded-lg border bg-muted p-1">
        {([
          ["traffic", "Traffic"],
          ["stats", "Stats"],
        ] as const).map(([key, label]) => (
          <button
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
        <span className="mx-1 my-1 w-px shrink-0 self-stretch bg-border" aria-hidden="true" />
        <PeriodSelector embedded period={period} onPeriod={onPeriod} />
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
        api<GrowthEvent[]>(`/api/v1/repositories/${id}/growth-events?period=${period}`)
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
}: {
  traffic: Dashboard["traffic"];
  events: GrowthEvent[];
  period: string;
}) {
  const option = useMemo(() => dashboardTrafficOption(traffic), [traffic]);

  return (
    <ChartCard title="Traffic" events={events} period={period} context="portfolio:traffic">
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

function StatsChart({
  points,
  events,
  period,
}: {
  points: Dashboard["stats"]["points"];
  events: GrowthEvent[];
  period: string;
}) {
  const option = useMemo(() => statsChartOption(points), [points]);
  return (
    <ChartCard title="Stats" events={events} period={period} context="portfolio:stats">
      {points.length === 0 ? (
        <div className="rounded-lg border border-dashed px-4 py-10 text-center text-sm text-muted-foreground">
          <div className="font-medium text-foreground">No stats for this period.</div>
        </div>
      ) : (
        <PersistentECharts
          chartId={dashboardStatsChartId()}
          series={STATS_SERIES}
          option={option}
          style={{ height: 360, width: "100%" }}
        />
      )}
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

function RankLink({ move }: { move: RankHighlight }) {
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
              const problems = repositoryProblemLines(row).join(" ");
              return (
                <tr
                  key={row.id}
                  className="cursor-pointer border-t hover:bg-muted/60"
                  onClick={() => navigate(`/repositories/${row.id}`)}
                >
                  <td className="max-w-0 px-5 py-3 align-top">
                    {problems && (
                      <span role="img" className="mr-1 text-amber-700" title={problems} aria-label={problems}>
                        !
                      </span>
                    )}
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
        const next = parseGitHubAccess(await api<unknown>("/api/v1/system/status"));
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
            <Link to="/repositories" className="mt-2 inline-block">
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
