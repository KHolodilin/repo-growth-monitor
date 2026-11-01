import { useCallback, useEffect, useRef, useState } from "react";
import { Link, NavLink, Outlet, useLocation } from "react-router-dom";
import { api, ApiError, type Dashboard } from "../lib/api";
import { useAuth } from "../lib/AuthContext";
import { canMutate, mutationBlockedReason, type Me } from "../lib/auth";
import { collectButtonLabel, collectionInProgress, headerProgress } from "../lib/collectProgress";
import { collectionBlocked, loadGitHubAccess, tokenBanner, type GitHubAccess } from "../lib/githubAccess";
import { headerSchedule } from "../lib/setupChecklist";
import { formatSyncTime } from "../lib/utils";
import { LogoMark, SignInIcon } from "./icons";

export function Layout() {
  const location = useLocation();
  const { me, loading: authLoading, logout } = useAuth();
  const repositoryId = repositoryIdFromPath(location.pathname);
  const [sync, setSync] = useState<string | null>(null);
  const [rows, setRows] = useState<Dashboard["repositories"] | null>(null);
  const [collecting, setCollecting] = useState(false);
  const [watching, setWatching] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [access, setAccess] = useState<GitHubAccess | null>(null);
  const syncLabel = formatSyncTime(sync) ?? "—";
  const progress = headerProgress({ repositoryId, rows: rows ?? [], watching });
  const live = watching || (rows ?? []).some((row) => collectionInProgress(row.collectionStatus));
  const mutateBlocked = mutationBlockedReason(authLoading ? null : me);
  const allowMutations = canMutate(me) && !authLoading;

  const refreshStatus = useCallback(async () => {
    const dashboard = await api<Dashboard>("/api/v1/dashboard?period=30d");
    if (!dashboard?.repositories) {
      setRows([]);
      return dashboard;
    }
    setSync(dashboard.lastSyncAt);
    setRows(dashboard.repositories);
    return dashboard;
  }, []);

  useEffect(() => {
    let cancelled = false;
    refreshStatus().catch(() => {
      if (!cancelled) {
        setSync(null);
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
        await api(`/api/v1/repositories/${onlyId}/collect`, { method: "POST" });
      } else {
        const dashboard = await api<Dashboard>("/api/v1/dashboard?period=30d");
        const ids = dashboard?.repositories?.map((row) => row.id) ?? [];
        await Promise.all(ids.map((id) => api(`/api/v1/repositories/${id}/collect`, { method: "POST" })));
        if (dashboard?.repositories) {
          setSync(dashboard.lastSyncAt);
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
  const noToken = access?.tokenState === "MISSING";
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
      {progress && (
        <div className="border-b bg-card">
          <p className="mx-auto max-w-6xl px-4 py-2 text-sm md:px-6">{progress}</p>
        </div>
      )}
      {tokenBanner(access) && (
        <div className="border-b bg-amber-50 text-amber-950">
          <p className="mx-auto max-w-6xl px-4 py-2 text-sm md:px-6">{tokenBanner(access)}</p>
        </div>
      )}
      <main className="mx-auto w-full max-w-6xl flex-1 px-4 py-6 md:px-6 md:py-8">
        <Outlet context={{ allowMutations, mutateBlocked }} />
      </main>
      <footer className="mt-auto border-t bg-card">
        <div className="mx-auto max-w-6xl space-y-1 px-4 py-4 text-xs text-muted-foreground md:px-6">
          <div>
            <span className="font-medium text-foreground">About data.</span> Data is collected once per day. Referrers and
            paths show the latest GitHub snapshot; History has the change between snapshots.
          </div>
          {access?.planner && <div>{headerSchedule(access.planner)}</div>}
          <div>Last sync: {syncLabel}</div>
          {error && <div className="text-sm text-red-600">{error}</div>}
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
}: {
  me: Me;
  loading: boolean;
  onLogout: () => void;
  collect: CollectMenuAction | null;
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
                to="/repositories"
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

function repositoryIdFromPath(pathname: string): number | null {
  const match = pathname.match(/^\/repositories\/(\d+)(?:\/|$)/);
  if (!match) {
    return null;
  }
  return Number(match[1]);
}
