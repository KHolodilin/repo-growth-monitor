import { useEffect, useState } from "react";
import { Link, useLocation, useNavigate, useParams, useSearchParams } from "react-router-dom";
import { api, ApiError, type GrowthEvent } from "../lib/api";
import { DashboardPresentation } from "../pages/DashboardPage";
import type { DashboardViewData } from "../lib/dashboardViewData";
import { DashboardViewContext, useDashboardView } from "../lib/dashboardView";
import { PERIODS, type Period } from "../components/PeriodSelector";
import { readPublicPreference, writePublicPreference } from "../lib/publicPrefs";
import { findOwnerByLogin } from "../lib/catalogApi";
import { publicScope } from "../lib/publicDashboard";
import { PublicNotFound } from "./PublicLayout";

type OwnerAbout = {
  ownerId: string;
  login: string;
  ownerType?: string | null;
};

/** Public owner portfolio only. Repository pages use RepositoryDetailsPage. */
export function PublicScopePage() {
  const { owner = "" } = useParams();
  const location = useLocation();
  const navigate = useNavigate();
  const layoutView = useDashboardView();
  const [params, setParams] = useSearchParams();
  const [about, setAbout] = useState<OwnerAbout | null>(null);
  const [model, setModel] = useState<DashboardViewData | null>(null);
  const [events, setEvents] = useState<GrowthEvent[]>([]);
  const [error, setError] = useState<Error | null>(null);
  const [busy, setBusy] = useState(true);
  const [retry, setRetry] = useState(0);
  const scope = about ? publicScope({ owner: { id: about.ownerId, login: about.login } }) : "";
  const stored = scope
    ? readPublicPreference(scope, "period", (v): v is Period => PERIODS.includes(v as Period))
    : undefined;
  const period: Period = PERIODS.includes(params.get("period") as Period)
    ? (params.get("period") as Period)
    : (stored ?? "7d");
  const rawTab = params.get("tab");
  const tab = rawTab === "stats" ? "stats" : "traffic";
  const ownerLoadKey = `${owner}:${retry}:${location.pathname}:${location.search}`;
  const [seenOwnerLoadKey, setSeenOwnerLoadKey] = useState(ownerLoadKey);
  if (ownerLoadKey !== seenOwnerLoadKey) {
    setSeenOwnerLoadKey(ownerLoadKey);
    setAbout(null);
    setModel(null);
    setError(null);
    setBusy(true);
  }
  const detailLoadKey = about ? `${publicScope({ owner: { id: about.ownerId, login: about.login } })}:${period}:${retry}` : null;
  const [seenDetailLoadKey, setSeenDetailLoadKey] = useState(detailLoadKey);
  if (detailLoadKey !== seenDetailLoadKey) {
    setSeenDetailLoadKey(detailLoadKey);
    if (about) {
      setBusy(true);
      setError(null);
      setModel(null);
    }
  }

  useEffect(() => {
    const abort = new AbortController();
    findOwnerByLogin(owner)
      .then(async (card) => {
        if (abort.signal.aborted) return;
        if (!card) {
          throw new ApiError(404, "Owner not found");
        }
        const next = await api<OwnerAbout>(`/api/v1/owners/${encodeURIComponent(card.ownerId)}`, {
          signal: abort.signal,
        });
        if (abort.signal.aborted) return;
        const canonical = `/${next.login}`;
        if (canonical !== location.pathname.replace(/\/$/, "")) {
          void navigate(canonical + location.search, { replace: true });
          return;
        }
        setAbout(next);
      })
      .catch((err: unknown) => {
        if (!abort.signal.aborted) {
          setError(err instanceof Error ? err : new Error(String(err)));
          setBusy(false);
        }
      });
    return () => abort.abort();
  }, [owner, retry, location.pathname, location.search, navigate]);

  useEffect(() => {
    if (!about) return;
    const abort = new AbortController();
    Promise.all([
      api<DashboardViewData>(
        `/api/v1/dashboard?period=${period}&owner=${encodeURIComponent(about.login)}`,
        { signal: abort.signal },
      ),
      Promise.resolve(
        api<GrowthEvent[]>(
          `/api/v1/owners/${encodeURIComponent(about.ownerId)}/growth-events?period=${period}`,
          { signal: abort.signal },
        ),
      ).catch(() => [] as GrowthEvent[]),
    ])
      .then(([dashboard, loadedEvents]) => {
        if (abort.signal.aborted) return;
        setEvents(loadedEvents);
        setModel(dashboard);
      })
      .catch((err: unknown) => {
        if (!abort.signal.aborted) {
          setError(err instanceof Error ? err : new Error(String(err)));
        }
      })
      .finally(() => {
        if (!abort.signal.aborted) setBusy(false);
      });
    return () => abort.abort();
  }, [about, period, retry]);

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
        <button
          className="min-h-11 rounded border px-4"
          onClick={() => setRetry((value) => value + 1)}
        >
          Retry
        </button>
      </section>
    );
  if (!about)
    return (
      <p role="status" className="py-10">
        Loading dashboard…
      </p>
    );

  const heading = about.login;
  const breadcrumb = (
    <nav aria-label="Breadcrumb" className="flex min-w-0 flex-wrap gap-2 text-sm">
      <Link to="/" className="text-primary">
        Home
      </Link>
      <span aria-hidden="true">›</span>
      <span aria-current="page">{about.login}</span>
    </nav>
  );
  const href = (id: number | string) => {
    const row = model?.repositories.find((item) => String(item.id) === String(id));
    const name = row?.fullName?.includes("/")
      ? row.fullName.slice(row.fullName.indexOf("/") + 1)
      : (row?.fullName ?? "");
    return `/${encodeURIComponent(about.login)}/${encodeURIComponent(name)}`;
  };

  return (
    <DashboardViewContext.Provider
      value={{
        ...layoutView,
        public: true,
        // Keep layout auth policy: anonymous hides collection !; signed-in keeps it.
        scopeKey: scope,
        heading,
        headerLeading: breadcrumb,
        repositoryHref: href,
        showRepositories: true,
        trafficEnabled: true,
      }}
    >
      <div className="space-y-4" key={scope}>
        {model && !model.lastSyncAt && model.state !== "READY" && (
          <p role="status" className="text-sm text-muted-foreground">
            No saved snapshots yet.
          </p>
        )}
        {busy ? (
          <p role="status" className="py-12">
            Loading observations…
          </p>
        ) : model ? (
          <DashboardPresentation
            key={scope}
            data={model}
            period={period}
            onPeriod={changePeriod}
            tab={tab}
            onTab={changeTab}
            events={events}
          />
        ) : null}
      </div>
    </DashboardViewContext.Provider>
  );
}
