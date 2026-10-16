import { useEffect, useState } from "react";
import { NavLink, Outlet, useLocation } from "react-router-dom";
import { api, type Dashboard } from "../lib/api";
import { collectionBlocked, loadGitHubAccess, tokenBanner, type GitHubAccess } from "../lib/githubAccess";
import { cn, formatSyncTime } from "../lib/utils";
import { DashboardIcon, LogoMark, RepositoriesIcon } from "./icons";
import { Button } from "./ui";

export function Layout() {
  const location = useLocation();
  const dashboardActive = location.pathname === "/" || location.pathname === "/dashboard";
  const [sync, setSync] = useState<string | null>(null);
  const [collecting, setCollecting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [access, setAccess] = useState<GitHubAccess | null>(null);
  const syncLabel = formatSyncTime(sync) ?? "—";

  async function refreshStatus() {
    const dashboard = await api<Dashboard>("/api/v1/dashboard?period=30d");
    if (!dashboard?.repositories) {
      return;
    }
    setSync(dashboard.lastSyncAt);
    return dashboard;
  }

  useEffect(() => {
    let cancelled = false;
    refreshStatus().catch(() => {
      if (!cancelled) {
        setSync(null);
      }
    });
    void loadGitHubAccess().then((next) => {
      if (!cancelled) {
        setAccess(next);
      }
    });
    return () => {
      cancelled = true;
    };
  }, [location.pathname]);

  async function collectNow() {
    setCollecting(true);
    setError(null);
    try {
      const onlyId = repositoryIdFromPath(location.pathname);
      if (onlyId != null) {
        await api(`/api/v1/repositories/${onlyId}/collect`, { method: "POST" });
      } else {
        const dashboard = await api<Dashboard>("/api/v1/dashboard?period=30d");
        const ids = dashboard?.repositories?.map((row) => row.id) ?? [];
        await Promise.all(ids.map((id) => api(`/api/v1/repositories/${id}/collect`, { method: "POST" })));
        if (dashboard?.repositories) {
          setSync(dashboard.lastSyncAt);
        }
      }
      window.dispatchEvent(new Event("repos-collected"));
      await refreshStatus();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Collect failed");
    } finally {
      setCollecting(false);
    }
  }

  return (
    <div className="flex min-h-screen flex-col bg-background">
      <header className="border-b bg-card">
        <div className="mx-auto flex max-w-6xl items-center justify-between gap-4 px-6 py-3.5">
          <NavLink to="/" className="flex items-center gap-2.5 text-slate-900" aria-label="Repo Growth Monitor">
            <LogoMark className="h-8 w-8 shrink-0 object-contain" />
            <span className="flex items-baseline gap-1 text-[15px] leading-none tracking-tight">
              <span className="font-bold">Repo Growth</span>
              <span className="font-normal">Monitor</span>
            </span>
          </NavLink>
          <nav className="flex items-center gap-6 text-sm">
            <Button className="py-1.5" disabled={collecting || collectionBlocked(access)} onClick={() => void collectNow()}>
              {collecting ? "Collecting..." : "Collect now"}
            </Button>
            <NavLink
              to="/"
              end
              className={cn(
                "flex items-center gap-2",
                dashboardActive ? "font-medium text-primary" : "text-muted-foreground hover:text-foreground",
              )}
            >
              <DashboardIcon className="h-6 w-6" />
              Dashboard
            </NavLink>
            <NavLink to="/repositories" className={navClass}>
              <RepositoriesIcon className="h-6 w-6" />
              Repositories
            </NavLink>
          </nav>
        </div>
      </header>
      {tokenBanner(access) && (
        <div className="border-b bg-amber-50 text-amber-950">
          <p className="mx-auto max-w-6xl px-6 py-2 text-sm">{tokenBanner(access)}</p>
        </div>
      )}
      <main className="mx-auto w-full max-w-6xl flex-1 px-6 py-8">
        <Outlet />
      </main>
      <footer className="mt-auto border-t bg-card">
        <div className="mx-auto max-w-6xl space-y-1 px-6 py-4 text-xs text-muted-foreground">
          <div>
            <span className="font-medium text-foreground">About data.</span> Data is collected once per day. Referrers and
            paths show the latest GitHub snapshot; History has the change between snapshots.
          </div>
          <div>Last sync: {syncLabel}</div>
          {error && <div className="text-sm text-red-600">{error}</div>}
        </div>
      </footer>
    </div>
  );
}

function repositoryIdFromPath(pathname: string): number | null {
  const match = pathname.match(/^\/repositories\/(\d+)(?:\/|$)/);
  if (!match) {
    return null;
  }
  return Number(match[1]);
}

function navClass({ isActive }: { isActive: boolean }) {
  return cn(
    "flex items-center gap-2",
    isActive ? "font-medium text-primary" : "text-muted-foreground hover:text-foreground",
  );
}
