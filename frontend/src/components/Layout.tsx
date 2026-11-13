import { useCallback, useEffect, useRef, useState } from "react";
import { Link, NavLink, Outlet, useLocation } from "react-router-dom";
import { api, ApiError, type Dashboard } from "../lib/api";
import { useAuth } from "../lib/AuthContext";
import { canMutate, mutationBlockedReason, type Me } from "../lib/auth";
import { collectButtonLabel, collectionInProgress, headerProgress } from "../lib/collectProgress";
import { collectionBlocked, loadGitHubAccess, tokenBanner, type GitHubAccess } from "../lib/githubAccess";
import { headerSchedule } from "../lib/setupChecklist";
import { RouteScroll } from "./RouteScroll";
import { LogoMark, SignInIcon } from "./icons";

const PROJECT_GITHUB = "https://github.com/KHolodilin/repo-growth-monitor";

export type RunAllMenuAction = {
  locationKey: string;
  label: string;
  disabled: boolean;
  onRun: () => void;
};

export function Layout() {
  const location = useLocation();
  const [runAll, setRunAll] = useState<RunAllMenuAction | null>(null);
  const { me, loading: authLoading, logout } = useAuth();
  const [rows, setRows] = useState<Dashboard["repositories"] | null>(null);
  const ownerScope = ownerLoginFromPath(location.pathname);
  const repositoryId = repositoryIdFromPath(location.pathname, rows);
  const [collecting, setCollecting] = useState(false);
  const [watching, setWatching] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [access, setAccess] = useState<GitHubAccess | null>(null);
  const [aboutOpen, setAboutOpen] = useState(false);
  const progressRows = ownerScope
    ? (rows ?? []).filter((row) => row.fullName.toLowerCase().startsWith(`${ownerScope.toLowerCase()}/`))
    : (rows ?? []);
  const progress =
    repositoryId != null || ownerScope != null
      ? headerProgress({ repositoryId, rows: progressRows, watching })
      : null;
  const live = watching || progressRows.some((row) => collectionInProgress(row.collectionStatus));
  const mutateBlocked = mutationBlockedReason(authLoading ? null : me);
  const allowMutations = canMutate(me) && !authLoading;
  const showOperationalStatus = !authLoading && me.authenticated;

  const refreshStatus = useCallback(async () => {
    const dashboard = await api<Dashboard>("/api/v1/admin/dashboard?period=30d");
    if (!dashboard?.repositories) {
      setRows([]);
      return dashboard;
    }
    setRows(dashboard.repositories);
    return dashboard;
  }, []);

  useEffect(() => {
    let cancelled = false;
    refreshStatus().catch(() => {
      if (!cancelled) {
        setRows([]);
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
  }, [location.pathname, refreshStatus]);

  useEffect(() => {
    if (!live) {
      return;
    }
    const timer = window.setInterval(() => {
      refreshStatus()
        .then((dashboard) => {
          const stillActive = (dashboard?.repositories ?? []).some((row) => collectionInProgress(row.collectionStatus));
          if (!stillActive) {
            setWatching(false);
          }
        })
        .catch(() => undefined);
    }, 2500);
    return () => window.clearInterval(timer);
  }, [live, refreshStatus]);

  async function collectNow() {
    if (!allowMutations) {
      setError(mutateBlocked ?? "Sign in as an admin to collect.");
      return;
    }
    setCollecting(true);
    setWatching(true);
    setError(null);
    try {
      const onlyId = repositoryIdFromPath(location.pathname);
      if (onlyId != null) {
        await api(`/api/v1/admin/repositories/${onlyId}/collect`, { method: "POST" });
      } else {
        const dashboard = await api<Dashboard>("/api/v1/admin/dashboard?period=30d");
        const ids = dashboard?.repositories?.map((row) => row.id) ?? [];
        await Promise.all(ids.map((id) => api(`/api/v1/admin/repositories/${id}/collect`, { method: "POST" })));
        if (dashboard?.repositories) {
          setRows(dashboard.repositories);
        }
      }
      window.dispatchEvent(new Event("repos-collected"));
      const refreshed = await refreshStatus();
      const stillActive = (refreshed?.repositories ?? []).some((row) => collectionInProgress(row.collectionStatus));
      if (!stillActive) {
        setWatching(false);
      }
    } catch (err) {
      setWatching(false);
      if (err instanceof ApiError && err.status === 401) {
        setError("Session expired. Sign in again.");
      } else {
        setError(err instanceof Error ? err.message : "Collect failed");
      }
    } finally {
      setCollecting(false);
    }
  }

  const label = collectButtonLabel(rows == null ? null : rows.length, repositoryId != null);
  const collectDisabled =
    collecting || collectionBlocked(access) || (repositoryId == null && rows != null && rows.length === 0);
  const noToken = showOperationalStatus && access?.tokenState === "MISSING";
  const accessBanner = showOperationalStatus ? tokenBanner(access) : null;
  document.title = noToken ? "Repo Growth Monitor — no token" : "Repo Growth Monitor";

  return (
    <div className="flex min-h-screen flex-col bg-background">
      <header className="border-b bg-card">
        <div className="mx-auto flex max-w-6xl items-center justify-between gap-3 px-4 py-3.5 md:px-6">
          <div className="flex min-w-0 items-center gap-3">
            <NavLink
              to="/"
              className="flex min-w-0 items-center gap-2.5 text-slate-900"
              aria-label="Repo Growth Monitor"
            >
              <LogoMark className="h-8 w-8 shrink-0 object-contain" />
              <span className="truncate whitespace-nowrap text-[15px] leading-none tracking-tight">
                <span className="font-bold">Repo Growth</span> <span className="font-normal">Monitor</span>
              </span>
            </NavLink>
            {noToken && (
              <span className="hidden rounded-md bg-amber-100 px-2 py-1 text-xs font-medium text-amber-950 sm:inline">
                No GitHub token
              </span>
            )}
          </div>
          <AuthChrome
            me={me}
            loading={authLoading}
            onLogout={() => void logout()}
            runAll={allowMutations && runAll?.locationKey === location.key ? runAll : null}
            collect={
              allowMutations
                ? {
                    label,
                    disabled: collectDisabled,
                    onCollect: () => void collectNow(),
                  }
                : null
            }
          />
        </div>
      </header>
      {showOperationalStatus && progress && (
        <div className="border-b bg-card">
          <p className="mx-auto max-w-6xl px-4 py-2 text-sm md:px-6">{progress}</p>
        </div>
      )}
      {accessBanner && (
        <div className="border-b bg-amber-50 text-amber-950">
          <p className="mx-auto max-w-6xl px-4 py-2 text-sm md:px-6">{accessBanner}</p>
        </div>
      )}
      <main className="mx-auto w-full max-w-6xl flex-1 px-4 py-6 md:px-6 md:py-8">
        <RouteScroll />
        <Outlet context={{ allowMutations, mutateBlocked, setRunAll }} />
      </main>
      <footer className="mt-auto border-t bg-card">
        <div className="mx-auto max-w-6xl space-y-2 px-4 py-4 text-xs text-muted-foreground md:px-6">
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
          {aboutOpen && (
            <div className="space-y-1">
              <p>
                Data is collected once per day. Referrers and paths show the latest GitHub snapshot; History has the change
                between snapshots.
              </p>
              {showOperationalStatus && access?.planner ? <p>{headerSchedule(access.planner)}</p> : null}
            </div>
          )}
          {showOperationalStatus && error && <div className="text-sm text-red-600">{error}</div>}
        </div>
      </footer>
    </div>
  );
}

type CollectMenuAction = {
  label: string;
  disabled: boolean;
  onCollect: () => void;
};

function AuthChrome({
  me,
  loading,
  onLogout,
  collect,
  runAll,
}: {
  me: Me;
  loading: boolean;
  onLogout: () => void;
  collect: CollectMenuAction | null;
  runAll: RunAllMenuAction | null;
}) {
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);

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

  if (loading) {
    return <span className="text-muted-foreground">…</span>;
  }

  const authenticated = me.authenticated;
  const identity = me.displayName?.trim() || me.userId || "Account";
  const initial = identity.charAt(0).toUpperCase();

  return (
    <div className="relative shrink-0" ref={rootRef}>
      <button
        type="button"
        className={
          authenticated
            ? "flex h-8 w-8 items-center justify-center rounded-full bg-muted text-sm font-medium text-foreground hover:bg-muted/80"
            : "flex h-8 w-8 items-center justify-center rounded-full text-muted-foreground hover:bg-muted hover:text-foreground"
        }
        aria-expanded={open}
        aria-haspopup="menu"
        aria-label="Account menu"
        onClick={() => setOpen((value) => !value)}
      >
        {authenticated ? initial : <SignInIcon className="h-6 w-6" />}
      </button>
      {open && (
        <div
          role="menu"
          className="absolute right-0 z-50 mt-2 w-56 overflow-hidden rounded-md border bg-card text-sm shadow-md"
        >
          {authenticated ? (
            <>
              <div className="border-b px-3 py-2.5">
                <div className="font-medium text-foreground">{identity}</div>
                {me.userId && me.userId !== identity && (
                  <div className="mt-0.5 text-xs text-muted-foreground">{me.userId}</div>
                )}
              </div>
              <Link
                to="/admin/repositories"
                role="menuitem"
                className="block w-full px-3 py-2 text-left text-muted-foreground hover:bg-muted hover:text-foreground"
                onClick={() => setOpen(false)}
              >
                Repositories
              </Link>
              {collect && (
                <button
                  type="button"
                  role="menuitem"
                  className="block w-full px-3 py-2 text-left text-muted-foreground hover:bg-muted hover:text-foreground disabled:cursor-not-allowed disabled:opacity-50"
                  disabled={collect.disabled}
                  onClick={() => {
                    setOpen(false);
                    collect.onCollect();
                  }}
                >
                  {collect.label}
                </button>
              )}
              {runAll && (
                <button
                  type="button"
                  role="menuitem"
                  className="block w-full px-3 py-2 text-left text-muted-foreground hover:bg-muted hover:text-foreground disabled:cursor-not-allowed disabled:opacity-50"
                  disabled={runAll.disabled}
                  onClick={() => {
                    setOpen(false);
                    runAll.onRun();
                  }}
                >
                  {runAll.label}
                </button>
              )}
              <button
                type="button"
                role="menuitem"
                className="block w-full px-3 py-2 text-left text-muted-foreground hover:bg-muted hover:text-foreground"
                onClick={() => {
                  setOpen(false);
                  onLogout();
                }}
              >
                Sign out
              </button>
            </>
          ) : (
            <a
              href={me.loginUrl}
              role="menuitem"
              className="block w-full px-3 py-2 text-left font-medium text-primary hover:bg-muted"
              onClick={() => setOpen(false)}
            >
              Sign in
            </a>
          )}
        </div>
      )}
    </div>
  );
}

/** Owner Portfolio path `/:owner` — not home, not repository, not admin. */
function ownerLoginFromPath(pathname: string): string | null {
  const parts = pathname.split("/").filter(Boolean);
  if (parts.length !== 1 || parts[0] === "admin") {
    return null;
  }
  return decodeURIComponent(parts[0]);
}

function repositoryIdFromPath(pathname: string, rows: Dashboard["repositories"] | null = null): number | null {
  const admin = pathname.match(/^\/admin\/repositories\/(\d+)(?:\/|$)/);
  if (admin) {
    return Number(admin[1]);
  }
  const parts = pathname.match(/^\/([^/]+)\/([^/]+)(?:\/|$)/);
  if (!parts || !rows) {
    return null;
  }
  const fullName = `${decodeURIComponent(parts[1])}/${decodeURIComponent(parts[2])}`.toLowerCase();
  const row = rows.find((item) => item.fullName.toLowerCase() === fullName);
  return row?.id ?? null;
}
