import { useEffect, useState } from "react";
import { Link, useLocation, useNavigate, useParams, useSearchParams } from "react-router-dom";
import { api, ApiError, type GrowthEvent } from "../lib/api";
import { DashboardPresentation, type DashboardViewData } from "../pages/DashboardPage";
import { DashboardViewContext } from "../lib/dashboardView";
import { PERIODS, type Period } from "../components/PeriodSelector";
import { readPublicPreference, writePublicPreference } from "../lib/publicPrefs";
import {
  publicBase,
  publicScope,
  publicRepositoryRows,
  adaptPublicDashboard,
  historyRows,
  publicEvents,
  type PublicOverview,
  type PublicRow,
  type StatsResponse,
  type TrafficResponse,
} from "../lib/publicDashboard";
import { PublicNotFound } from "./PublicLayout";
import { PublicModules } from "./PublicModules";

/** Public owner portfolio only. Repository pages use RepositoryDetailsPage. */
export function PublicScopePage() {
  const { owner = "" } = useParams();
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
  const rawTab = params.get("tab");
  const tab = rawTab === "stats" ? "stats" : "traffic";

  useEffect(() => {
    const abort = new AbortController();
    setOverview(null);
    setModel(null);
    setRows([]);
    setError(null);
    setBusy(true);
    api<PublicOverview>(publicBase(owner), { signal: abort.signal })
      .then((next) => {
        if (abort.signal.aborted) return;
        if (next.repository) {
          navigate(`/${encodeURIComponent(next.owner.login)}/${encodeURIComponent(next.repository.name)}${location.search}`, {
            replace: true,
          });
          return;
        }
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
  }, [owner, retry, location.pathname, location.search, navigate]);

  useEffect(() => {
    if (!overview) return;
    const abort = new AbortController();
    const base = publicBase(overview.owner.login);
    setBusy(true);
    setError(null);
    setModel(null);
    Promise.all([
      api<StatsResponse>(`${base}/stats?period=${period}`, { signal: abort.signal }),
      historyRows(`${base}/stats-history?period=${period}`, abort.signal),
      overview.modules.traffic ? api<TrafficResponse>(`${base}/traffic?period=${period}`, { signal: abort.signal }) : Promise.resolve(null),
      overview.modules.traffic ? historyRows(`${base}/traffic-history?period=${period}`, abort.signal) : Promise.resolve([]),
      publicRepositoryRows(base, period, abort.signal),
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
    query.set("tab", next === "stats" ? "stats" : "traffic");
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

  const heading = overview.owner.login;
  const breadcrumb = (
    <nav aria-label="Breadcrumb" className="flex min-w-0 flex-wrap gap-2 text-sm">
          <Link to="/" className="text-primary">
            Home
          </Link>
          <span aria-hidden="true">›</span>
          <span aria-current="page">{overview.owner.login}</span>
        </nav>
  );
  const href = (id: number | string) =>
    `/${encodeURIComponent(overview.owner.login)}/${encodeURIComponent(rows.find((row) => row.id === String(id))?.name ?? "")}`;

  return (
    <DashboardViewContext.Provider
      value={{
        public: true,
        scopeKey: scope,
        heading,
        headerLeading: breadcrumb,
        repositoryHref: href,
        showRepositories: true,
        trafficEnabled: overview.modules.traffic,
      }}
    >
      <div className="space-y-4" key={scope}>

        {overview.freshness.state === "EMPTY" && (
          <p role="status" className="text-sm text-muted-foreground">
            No saved snapshots yet.
          </p>
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
            onTab={changeTab}
            events={events}
          />
        ) : model ? (
          <PublicModules overview={overview} rows={rows} tab={tab} period={period} events={events} />
        ) : null}
      </div>
    </DashboardViewContext.Provider>
  );
}
