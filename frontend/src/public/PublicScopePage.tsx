import { useEffect, useState } from "react";
import { Link, useLocation, useNavigate, useParams, useSearchParams } from "react-router-dom";
import { api, ApiError, type GrowthEvent } from "../lib/api";
import { DashboardPresentation, type DashboardViewData } from "../pages/DashboardPage";
import { DashboardViewContext } from "../lib/dashboardView";
import { PERIODS, PeriodSelector, type Period } from "../components/PeriodSelector";
import { NavigationButton } from "../components/NavigationButton";
import { readPublicPreference, writePublicPreference } from "../lib/publicPrefs";
import { publicBase, publicScope, publicRepositoryRows, adaptPublicDashboard, historyRows, publicEvents, type PublicOverview, type PublicRow, type StatsResponse, type TrafficResponse } from "../lib/publicDashboard";
import { PublicNotFound } from "./PublicLayout";
import { PublicModules } from "./PublicModules";
import { cn } from "../lib/utils";

type RepoTab = "traffic" | "stats" | "search" | "topics" | "events";

export function PublicScopePage() {
  const { owner = "", repository } = useParams();
  const location = useLocation();
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const [overview, setOverview] = useState<PublicOverview | null>(null);
  const [model, setModel] = useState<DashboardViewData | null>(null);
  const [rows, setRows] = useState<PublicRow[]>([]);
  const [events, setEvents] = useState<GrowthEvent[]>([]);
  const [error, setError] = useState<Error | null>(null);
  const [busy, setBusy] = useState(true);
  const [retry, setRetry] = useState(0);
  const scope = overview ? publicScope(overview) : "";
  const stored = scope ? readPublicPreference(scope, "period", (v): v is Period => PERIODS.includes(v as Period)) : undefined;
  const period: Period = PERIODS.includes(params.get("period") as Period) ? (params.get("period") as Period) : stored ?? "30d";
  const isRepo = Boolean(overview?.repository);
  const rawTab = params.get("tab") === "growth-events" ? "events" : params.get("tab");
  const defaultTab: RepoTab = overview?.modules.traffic === false ? "stats" : "traffic";
  const requested = (rawTab as RepoTab | null) ?? defaultTab;
  const tab: RepoTab =
    !isRepo
      ? requested === "stats"
        ? "stats"
        : "traffic"
      : requested === "traffic" && overview?.modules.traffic === false
        ? "stats"
        : (["traffic", "stats", "search", "topics", "events"].includes(requested) ? requested : defaultTab);

  useEffect(() => {
    const abort = new AbortController();
    setOverview(null);
    setModel(null);
    setRows([]);
    setError(null);
    setBusy(true);
    api<PublicOverview>(publicBase(owner, repository), { signal: abort.signal })
      .then((next) => {
        if (abort.signal.aborted) return;
        if (next.canonicalPath !== location.pathname.replace(/\/$/, "")) {
          navigate(next.canonicalPath + location.search, { replace: true });
          return;
        }
        setOverview(next);
      })
      .catch((err) => {
        if (!abort.signal.aborted) {
          setError(err);
          setBusy(false);
        }
      });
    return () => abort.abort();
  }, [owner, repository, retry, location.pathname, location.search, navigate]);

  useEffect(() => {
    if (!overview) return;
    const abort = new AbortController();
    const base = publicBase(overview.owner.login, overview.repository?.name);
    setBusy(true);
    setError(null);
    setModel(null);
    const repoRows = overview.repository
      ? Promise.resolve([{ id: overview.repository.id, name: overview.repository.name }] as PublicRow[])
      : publicRepositoryRows(base, period, abort.signal);
    Promise.all([
      api<StatsResponse>(`${base}/stats?period=${period}`, { signal: abort.signal }),
      historyRows(`${base}/stats-history?period=${period}`, abort.signal),
      overview.modules.traffic ? api<TrafficResponse>(`${base}/traffic?period=${period}`, { signal: abort.signal }) : Promise.resolve(null),
      overview.modules.traffic ? historyRows(`${base}/traffic-history?period=${period}`, abort.signal) : Promise.resolve([]),
      repoRows,
      publicEvents(base, abort.signal),
    ])
      .then(([stats, statsRows, traffic, trafficRows, repositories, loadedEvents]) => {
        if (abort.signal.aborted) return;
        setRows(repositories);
        setEvents(loadedEvents);
        setModel(adaptPublicDashboard(overview, stats, statsRows, traffic, trafficRows, repositories));
      })
      .catch((err) => {
        if (!abort.signal.aborted) setError(err);
      })
      .finally(() => {
        if (!abort.signal.aborted) setBusy(false);
      });
    return () => abort.abort();
  }, [overview, period, retry]);

  const changePeriod = (next: Period) => {
    writePublicPreference(scope, "period", next);
    const query = new URLSearchParams(params);
    query.set("period", next);
    setParams(query);
  };
  const changeTab = (next: string) => {
    const query = new URLSearchParams(params);
    if (next === "traffic" || next === "stats") query.set("tab", next);
    else query.set("tab", next === "events" ? "growth-events" : next);
    setParams(query);
  };

  if (error instanceof ApiError && error.status === 404) return <PublicNotFound />;
  if (error)
    return (
      <section role="alert" className="space-y-3">
        <h1 className="text-xl font-semibold">Data unavailable</h1>
        <p>{error.message}</p>
        <button className="min-h-11 rounded border px-4" onClick={() => setRetry((value) => value + 1)}>
          Retry
        </button>
      </section>
    );
  if (!overview) return <p role="status" className="py-10">Loading dashboard…</p>;
  if ((tab === "search" && !overview.modules.search) || (tab === "topics" && !overview.modules.topics)) return <PublicNotFound />;

  const heading = overview.repository?.name ?? overview.owner.login;
  const href = (id: number | string) =>
    `/${encodeURIComponent(overview.owner.login)}/${encodeURIComponent(rows.find((row) => row.id === String(id))?.name ?? overview.repository?.name ?? "")}`;

  const repoTabs = (
    [
      ...(overview.modules.traffic ? ([["traffic", "Traffic", "Traffic"]] as const) : []),
      ["stats", "Stats", "Stats"] as const,
      ...(overview.modules.search ? ([["search", "Search Visibility", "Search"]] as const) : []),
      ...(overview.modules.topics ? ([["topics", "Topics Visibility", "Topics"]] as const) : []),
      ["events", "Growth Events", "Events"] as const,
    ] as const
  );

  return (
    <DashboardViewContext.Provider
      value={{
        public: true,
        scopeKey: scope,
        heading,
        repositoryHref: href,
        showRepositories: !overview.repository,
        trafficEnabled: overview.modules.traffic,
      }}
    >
      <div className="space-y-4" key={scope}>
        <nav aria-label="Breadcrumb" className="flex min-w-0 flex-wrap gap-2 text-sm">
          <Link to="/" className="text-primary">
            Home
          </Link>
          <span aria-hidden="true">›</span>
          {overview.repository ? (
            <>
              <Link to={`/${overview.owner.login}`} className="break-all text-primary">
                {overview.owner.login}
              </Link>
              <span aria-hidden="true">›</span>
              <span className="break-all" aria-current="page">
                {overview.repository.name}
              </span>
            </>
          ) : (
            <span aria-current="page">{overview.owner.login}</span>
          )}
        </nav>
        {overview.repository && (
          <label className="flex flex-wrap items-center gap-2 text-sm">
            Repository{" "}
            <PublicRepositorySwitcher owner={overview.owner.login} current={overview.repository.id} period={period} />
          </label>
        )}
        {overview.freshness.state === "EMPTY" && <p role="status" className="text-sm text-muted-foreground">No saved snapshots yet.</p>}
        {isRepo && (
          <div className="flex min-w-0 flex-col gap-3 md:flex-row md:flex-wrap md:items-center md:justify-between">
            <div className="grid w-full min-w-0 grid-cols-3 rounded-lg border bg-muted p-1 md:inline-flex md:w-auto">
              {repoTabs.map(([key, label, mobileLabel]) => (
                <NavigationButton
                  key={key}
                  label={label}
                  mobileLabel={mobileLabel}
                  aria-pressed={tab === key}
                  type="button"
                  className={cn(
                    "min-h-11 px-1 md:min-h-0 md:px-3",
                    tab === key ? "bg-card text-foreground shadow-xs" : "text-muted-foreground hover:text-foreground",
                  )}
                  onClick={() => changeTab(key)}
                />
              ))}
            </div>
            {(tab === "search" || tab === "topics" || tab === "events") && (
              <PeriodSelector fitMobile period={period} onPeriod={changePeriod} />
            )}
          </div>
        )}
        {busy ? (
          <p role="status" className="py-12">
            Loading observations…
          </p>
        ) : model && (tab === "traffic" || tab === "stats") ? (
          <DashboardPresentation
            key={scope}
            data={model}
            period={period}
            onPeriod={changePeriod}
            tab={tab}
            onTab={(next) => changeTab(next)}
            events={events}
          />
        ) : model ? (
          <>
            <h1 className="break-all text-2xl font-semibold">{heading}</h1>
            <PublicModules overview={overview} rows={rows} tab={tab} period={period} events={events} />
          </>
        ) : null}
        {!busy && overview.repository && tab === "traffic" && (
          <PublicModules overview={overview} rows={rows} tab="sources" period={period} events={events} />
        )}
      </div>
    </DashboardViewContext.Provider>
  );
}

function PublicRepositorySwitcher({
  owner,
  current,
  period,
}: {
  owner: string;
  current: string;
  period: Period;
}) {
  const [items, setItems] = useState<PublicRow[]>([]);
  const navigate = useNavigate();
  useEffect(() => {
    const abort = new AbortController();
    publicRepositoryRows(publicBase(owner), period, abort.signal)
      .then((next) => {
        if (!abort.signal.aborted) setItems(next);
      })
      .catch(() => undefined);
    return () => abort.abort();
  }, [owner, period]);
  return (
    <select
      className="min-h-11 w-full min-w-0 max-w-md rounded border bg-card p-2"
      aria-label="Select repository"
      value={current}
      onChange={(event) => {
        const next = items.find((item) => item.id === event.target.value);
        if (next) navigate(`/${encodeURIComponent(owner)}/${encodeURIComponent(next.name)}?period=${period}`);
      }}
    >
      {items.length ? (
        items.map((item) => (
          <option key={item.id} value={item.id}>
            {item.name}
          </option>
        ))
      ) : (
        <option value={current}>Current repository</option>
      )}
    </select>
  );
}
