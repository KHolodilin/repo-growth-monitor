import { useEffect, useState } from "react";
import { Link, Outlet, useLocation } from "react-router-dom";
import { LogoMark, SignInIcon } from "../components/icons";
import { api } from "../lib/api";
import { useAuth } from "../lib/AuthContext";
import { DashboardViewContext } from "../lib/dashboardView";
import { publicBase, type PublicOverview } from "../lib/publicDashboard";
import { formatSyncTime } from "../lib/utils";

const PROJECT_GITHUB = "https://github.com/KHolodilin/repo-growth-monitor";

export function PublicLayout() {
  const location = useLocation();
  const { me } = useAuth();
  const homePage = location.pathname === "/";
  const [syncAt, setSyncAt] = useState<string | null>(null);
  const [aboutOpen, setAboutOpen] = useState(false);
  const syncTime = formatSyncTime(syncAt);
  // Anonymous never sees collection-incomplete markers; signed-in users keep them.
  const collectionExpected = me.authenticated;

  const parts = location.pathname.split("/").filter(Boolean);
  const syncPathKey = homePage || parts[0] === "admin" ? null : location.pathname;
  const [seenSyncPathKey, setSeenSyncPathKey] = useState(syncPathKey);
  if (syncPathKey !== seenSyncPathKey) {
    setSeenSyncPathKey(syncPathKey);
    if (syncPathKey === null) {
      setSyncAt(null);
    }
  }

  useEffect(() => {
    if (syncPathKey === null) {
      return;
    }
    const [owner, repository] = syncPathKey.split("/").filter(Boolean);
    const abort = new AbortController();
    api<PublicOverview>(publicBase(owner, repository), { signal: abort.signal })
      .then((overview) => {
        if (!abort.signal.aborted) {
          setSyncAt(overview.freshness.lastSuccessfulSnapshotAt ?? null);
        }
      })
      .catch(() => {
        if (!abort.signal.aborted) {
          setSyncAt(null);
        }
      });
    return () => abort.abort();
  }, [syncPathKey]);

  return (
    <DashboardViewContext.Provider value={{ public: true, collectionExpected }}>
      <div className="flex min-h-screen flex-col bg-background text-foreground">
        <header className="border-b bg-card">
          <div className="mx-auto flex max-w-6xl items-center justify-between gap-3 px-4 py-3.5 md:px-6">
            <Link
              to="/"
              className="flex min-w-0 items-center gap-2.5 text-slate-900"
              aria-label="Repo Growth Monitor"
            >
              <LogoMark className="h-8 w-8 shrink-0 object-contain" />
              <span className="truncate whitespace-nowrap text-[15px] leading-none tracking-tight">
                <span className="font-bold">Repo Growth</span>{" "}
                <span className="font-normal">Monitor</span>
              </span>
            </Link>
            <a
              href="/login"
              aria-label="Sign in"
              className="flex h-8 w-8 items-center justify-center rounded-full text-muted-foreground hover:bg-muted hover:text-foreground"
            >
              <SignInIcon className="h-6 w-6" />
            </a>
          </div>
        </header>
        <main className="mx-auto w-full max-w-6xl flex-1 px-4 py-6 md:px-6 md:py-8">
          <Outlet />
        </main>
        <footer className="mt-auto border-t bg-card">
          <div className="mx-auto max-w-6xl space-y-2 px-4 py-4 text-xs text-muted-foreground md:px-6">
            {homePage ? (
              <>
                <p>
                  Public dashboards use saved snapshots. Sign in only for repository management.
                </p>
                <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
                  <button
                    type="button"
                    className="font-medium text-primary hover:underline"
                    aria-expanded={aboutOpen}
                    onClick={() => setAboutOpen((value) => !value)}
                  >
                    About data
                  </button>
                  <span aria-hidden="true">·</span>
                  <a
                    className="font-medium text-primary hover:underline"
                    href={PROJECT_GITHUB}
                    target="_blank"
                    rel="noreferrer"
                  >
                    GitHub
                  </a>
                </div>
              </>
            ) : (
              <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
                <p>
                  <span className="font-medium text-foreground">
                    {syncTime ? `Last sync: ${syncTime}` : "No successful sync yet"}
                  </span>
                </p>
                <button
                  type="button"
                  className="font-medium text-primary hover:underline"
                  aria-expanded={aboutOpen}
                  onClick={() => setAboutOpen((value) => !value)}
                >
                  About data
                </button>
              </div>
            )}
            {aboutOpen && (
              <p>
                Data is collected once per day. Referrers and paths show the latest GitHub snapshot;
                History has the change between snapshots.
              </p>
            )}
          </div>
        </footer>
      </div>
    </DashboardViewContext.Provider>
  );
}

export function PublicNotFound() {
  return (
    <section role="alert" className="space-y-3 py-16">
      <h1 className="text-2xl font-semibold">Page not found</h1>
      <p>This page is unavailable.</p>
      <Link to="/" className="text-primary underline">
        Home
      </Link>
    </section>
  );
}
