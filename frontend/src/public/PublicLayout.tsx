import { useEffect, useState } from "react";
import { Link, Outlet, useLocation } from "react-router-dom";
import { LogoMark, SignInIcon } from "../components/icons";
import { api } from "../lib/api";
import { publicBase, type PublicOverview } from "../lib/publicDashboard";
import { formatSyncTime } from "../lib/utils";

export function PublicLayout() {
  const location = useLocation();
  const [syncAt, setSyncAt] = useState<string | null>(null);
  const [aboutOpen, setAboutOpen] = useState(false);
  const syncTime = formatSyncTime(syncAt);

  useEffect(() => {
    const parts = location.pathname.split("/").filter(Boolean);
    if (parts.length === 0 || parts[0] === "admin") {
      setSyncAt(null);
      return;
    }
    const [owner, repository] = parts;
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
  }, [location.pathname]);

  return (
    <div className="flex min-h-screen flex-col bg-background text-foreground">
      <header className="border-b bg-card">
        <div className="mx-auto flex max-w-6xl items-center justify-between px-4 py-4">
          <Link to="/" className="flex min-w-0 items-center gap-2 text-sm font-semibold" aria-label="Repo Growth Monitor home">
            <LogoMark className="h-7 w-7 shrink-0" />
            Repo Growth Monitor
          </Link>
          <a href="/login" aria-label="Sign in" className="inline-flex min-h-11 min-w-11 items-center justify-center rounded-full border">
            <SignInIcon className="h-6 w-6" />
          </a>
        </div>
      </header>
      <main className="mx-auto w-full max-w-6xl flex-1 p-4 sm:p-6">
        <Outlet />
      </main>
      <footer className="mt-auto border-t bg-card">
        <div className="mx-auto max-w-6xl space-y-2 px-4 py-4 text-xs text-muted-foreground md:px-6">
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
          {aboutOpen && (
            <p>
              Data is collected once per day. Referrers and paths show the latest GitHub snapshot; History has the change
              between snapshots.
            </p>
          )}
        </div>
      </footer>
    </div>
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

export function PublicHomePlaceholder() {
  return (
    <section className="space-y-4 py-12">
      <h1 className="text-3xl font-semibold">Understand how your repositories grow.</h1>
      <p>Explore saved traffic, stars, search visibility and topic rankings.</p>
    </section>
  );
}
