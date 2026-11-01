import { useEffect, useMemo, useState, type ReactNode } from "react";
import { Link } from "react-router-dom";
import { RepositoryAccessNote } from "../components/RepositoryAccessNote";
import { Button, Card } from "../components/ui";
import { api, type Repository } from "../lib/api";
import {
  PRIVATE_TRACKING,
  collectionBlocked,
  loadGitHubAccess,
  type GitHubAccess,
} from "../lib/githubAccess";
import { useMutationAccess } from "../lib/mutationAccess";
import { activityClass, formatActivityPresentation } from "../lib/utils";

const SEARCH_KEY = "rgm.repositories.search";
const VISIBILITY_KEY = "rgm.repositories.visibility";

type TrackingFilter = "all" | "tracked" | "untracked" | "no-access";
type VisibilityFilter = "all" | "public" | "private" | "internal";

export function RepositoriesPage() {
  const { allowMutations, blocked: mutateBlocked } = useMutationAccess();
  const [repos, setRepos] = useState<Repository[]>([]);
  const [access, setAccess] = useState<GitHubAccess | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [search, setSearch] = useState(() => readSession(SEARCH_KEY));
  const [visibilityStored, setVisibilityStored] = useState(() => readSession(VISIBILITY_KEY));
  const [trackingChoice, setTrackingChoice] = useState<TrackingFilter | null>(null);

  async function load(refresh = false) {
    setLoading(true);
    setError(null);
    try {
      let nextAccess = access;
      if (!nextAccess) {
        nextAccess = await loadGitHubAccess();
        setAccess(nextAccess);
      }
      if (refresh) {
        setRepos(await api<Repository[]>("/api/v1/repositories?refresh=true"));
        return;
      }
      let data = await api<Repository[]>("/api/v1/repositories?refresh=false");
      if (nextAccess.tokenState === "OK" && data.length === 0) {
        data = await api<Repository[]>("/api/v1/repositories?refresh=true");
      }
      setRepos(data);
    } catch (err) {
      setError((err as Error).message);
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void load(false);
  }, []);

  const listed = useMemo(
    () => (allowMutations ? repos : repos.filter((repo) => repo.visibility === "PUBLIC")),
    [allowMutations, repos],
  );
  const trackedCount = listed.filter((repo) => repo.trackingEnabled).length;
  const tracking: TrackingFilter = trackingChoice ?? (trackedCount > 0 ? "tracked" : "all");
  const showVisibility = allowMutations && !sameVisibility(listed);
  const showInternal = allowMutations && listed.some((repo) => repo.visibility === "INTERNAL");
  const showNoAccess = access?.tokenState !== "REJECTED" && listed.some((repo) => repo.accessible === false);
  const visibility = allowMutations
    ? resolveVisibility(listed, visibilityStored, showVisibility)
    : "public";

  const visible = useMemo(
    () => listed.filter((repo) => matches(repo, search, tracking, visibility, showVisibility)),
    [listed, search, tracking, visibility, showVisibility],
  );
  const privateHidden = allowMutations
    ? searched(listed, search).filter(
        (repo) => matchesTracking(repo, tracking) && repo.visibility === "PRIVATE" && visibility === "public",
      ).length
    : 0;
  const enableTargets = visible.filter((repo) => !repo.trackingEnabled && repo.accessible !== false);
  const disableTargets = visible.filter((repo) => repo.trackingEnabled);
  const showEnable = allowMutations && !(tracking === "all" && search.trim() === "") && enableTargets.length > 0;

  async function toggle(repo: Repository) {
    if (!allowMutations) {
      setError(mutateBlocked ?? "Admin role required");
      return;
    }
    if (repo.trackingEnabled && !window.confirm("Stop tracking this repository? Collection stops. Stored data stays.")) {
      return;
    }
    try {
      const updated = await api<Repository>(`/api/v1/repositories/${repo.id}/tracking`, {
        method: "POST",
        body: JSON.stringify({ enabled: !repo.trackingEnabled }),
      });
      setRepos((current) => current.map((item) => (item.id === updated.id ? { ...item, ...updated } : item)));
    } catch (err) {
      setError((err as Error).message);
    }
  }

  async function applyShown(enabled: boolean) {
    if (!allowMutations) {
      setError(mutateBlocked ?? "Admin role required");
      return;
    }
    const targets = enabled ? enableTargets : disableTargets;
    if (!enabled && !window.confirm("Turn tracking off for the shown repositories? Collection stops. Stored data stays.")) {
      return;
    }
    for (const repo of targets) {
      try {
        const updated = await api<Repository>(`/api/v1/repositories/${repo.id}/tracking`, {
          method: "POST",
          body: JSON.stringify({ enabled }),
        });
        setRepos((current) => current.map((item) => (item.id === updated.id ? { ...item, ...updated } : item)));
      } catch (err) {
        setError((err as Error).message);
        return;
      }
    }
  }

  function chooseVisibility(next: VisibilityFilter) {
    setVisibilityStored(next);
    writeSession(VISIBILITY_KEY, next);
  }

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold">Repositories</h1>
          <p className="text-sm text-muted-foreground">Select GitHub repositories to monitor.</p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          {showEnable && (
            <Button className="bg-card text-foreground hover:bg-muted" onClick={() => void applyShown(true)}>
              Start tracking ({enableTargets.length})
            </Button>
          )}
          {allowMutations && disableTargets.length > 0 && (
            <Button className="bg-card text-foreground hover:bg-muted" onClick={() => void applyShown(false)}>
              Stop tracking ({disableTargets.length})
            </Button>
          )}
          {allowMutations && (
            <Button disabled={loading || collectionBlocked(access)} onClick={() => void load(true)}>
              Refresh from GitHub
            </Button>
          )}
        </div>
      </div>
      {error && <p className="text-red-600">{error}</p>}
      <Card>
        <div className="space-y-4">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <p className="text-sm font-medium">
              Tracking {trackedCount} of {listed.length}
            </p>
            <input
              aria-label="Search repositories"
              className="w-full max-w-xs rounded-md border bg-background px-3 py-1.5 text-sm outline-hidden focus:border-primary"
              placeholder="owner/name"
              value={search}
              onChange={(event) => {
                setSearch(event.target.value);
                writeSession(SEARCH_KEY, event.target.value);
              }}
            />
          </div>
          <div className="flex flex-wrap items-center gap-3">
            <FilterGroup label="Tracking">
              {(["all", "tracked", "untracked"] as const).map((key) => (
                <FilterButton key={key} active={tracking === key} onClick={() => setTrackingChoice(key)}>
                  {TRACKING_LABEL[key]}
                </FilterButton>
              ))}
              {showNoAccess && (
                <FilterButton active={tracking === "no-access"} onClick={() => setTrackingChoice("no-access")}>
                  No access
                </FilterButton>
              )}
            </FilterGroup>
            {showVisibility && (
              <FilterGroup label="Visibility">
                <FilterButton active={visibility === "all"} onClick={() => chooseVisibility("all")}>
                  All
                </FilterButton>
                <FilterButton active={visibility === "public"} onClick={() => chooseVisibility("public")}>
                  Public
                </FilterButton>
                <FilterButton active={visibility === "private"} onClick={() => chooseVisibility("private")}>
                  Private
                </FilterButton>
                {showInternal && (
                  <FilterButton active={visibility === "internal"} onClick={() => chooseVisibility("internal")}>
                    Internal
                  </FilterButton>
                )}
              </FilterGroup>
            )}
            {privateHidden > 0 && (
              <button type="button" className="text-sm text-primary hover:underline" onClick={() => chooseVisibility("private")}>
                {privateHidden} private hidden
              </button>
            )}
          </div>
          <div className="space-y-3">
            {visible.map((repo) => (
              <label key={repo.id} className="flex items-center justify-between gap-4 rounded-lg border px-3 py-3">
                <div className="flex min-w-0 items-start gap-3">
                  {allowMutations && (
                    <input
                      type="checkbox"
                      className="mt-1"
                      checked={repo.trackingEnabled}
                      disabled={repo.accessible === false && !repo.trackingEnabled}
                      onChange={() => void toggle(repo)}
                    />
                  )}
                  <div className="min-w-0">
                    <div className="flex flex-wrap items-center gap-2">
                      <Link className="break-words [overflow-wrap:anywhere] font-medium text-primary hover:underline" to={`/repositories/${repo.id}`}>
                        {repo.fullName}
                      </Link>
                      {repo.visibility === "PRIVATE" && (
                        <span className="rounded bg-muted px-1.5 py-0.5 text-xs font-medium text-foreground">Private</span>
                      )}
                    </div>
                    <div className="text-xs text-muted-foreground">
                      {repo.visibility === "PRIVATE" ? "Private" : repo.visibility} · {repo.stars} stars · {repo.owner.login}
                    </div>
                    {repo.visibility === "PRIVATE" && repo.trackingEnabled && (
                      <p className="mt-1 text-xs text-muted-foreground">{PRIVATE_TRACKING}</p>
                    )}
                    <RepositoryAccessNote
                      accessible={repo.accessible}
                      trafficPermissionMissing={repo.trafficPermissionMissing}
                      tokenState={access?.tokenState}
                    />
                  </div>
                </div>
                <div className={`shrink-0 text-sm ${activityClass(repo.activityStatus)}`}>
                  {formatActivityPresentation(repo.activityStatus, repo.lastActivityAt)}
                </div>
              </label>
            ))}
            {listed.length === 0 && !loading && (
              <p className="text-sm text-muted-foreground">
                {allowMutations
                  ? "No repositories yet. Configure GITHUB_TOKEN and refresh."
                  : "No public repositories to show."}
              </p>
            )}
          </div>
        </div>
      </Card>
    </div>
  );
}

const TRACKING_LABEL: Record<Exclude<TrackingFilter, "no-access">, string> = {
  all: "All",
  tracked: "Tracked",
  untracked: "Not tracked",
};

function FilterGroup({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="inline-flex rounded-lg border bg-muted p-1" role="group" aria-label={label}>
      {children}
    </div>
  );
}

function FilterButton({ active, onClick, children }: { active: boolean; onClick: () => void; children: ReactNode }) {
  return (
    <button
      type="button"
      className={active ? "rounded-md bg-card px-3 py-1.5 text-sm font-medium shadow-xs" : "rounded-md px-3 py-1.5 text-sm font-medium text-muted-foreground hover:text-foreground"}
      aria-pressed={active}
      onClick={onClick}
    >
      {children}
    </button>
  );
}

function readSession(key: string): string {
  try {
    return sessionStorage.getItem(key) ?? "";
  } catch {
    return "";
  }
}

function writeSession(key: string, value: string) {
  try {
    sessionStorage.setItem(key, value);
  } catch {
    // The page still works when the browser blocks session storage.
  }
}

function sameVisibility(repos: Repository[]): boolean {
  return new Set(repos.map((repo) => repo.visibility)).size <= 1;
}

function resolveVisibility(repos: Repository[], stored: string, shown: boolean): VisibilityFilter {
  if (!shown) {
    return "all";
  }
  if ((stored === "all" || stored === "public" || stored === "private" || stored === "internal") && (stored !== "internal" || repos.some((repo) => repo.visibility === "INTERNAL"))) {
    return stored;
  }
  if (repos.some((repo) => repo.visibility === "PUBLIC") && repos.some((repo) => repo.visibility === "PRIVATE")) {
    return "public";
  }
  return "all";
}

function searched(repos: Repository[], search: string): Repository[] {
  const query = search.trim().toLowerCase();
  if (!query) {
    return repos;
  }
  return repos.filter(
    (repo) => repo.fullName.toLowerCase().includes(query) || repo.owner.login.toLowerCase().includes(query),
  );
}

function matchesTracking(repo: Repository, tracking: TrackingFilter): boolean {
  if (tracking === "tracked") {
    return repo.trackingEnabled;
  }
  if (tracking === "untracked") {
    return !repo.trackingEnabled;
  }
  if (tracking === "no-access") {
    return repo.accessible === false;
  }
  return true;
}

function matches(repo: Repository, search: string, tracking: TrackingFilter, visibility: VisibilityFilter, showVisibility: boolean): boolean {
  if (!searched([repo], search).length) {
    return false;
  }
  if (!matchesTracking(repo, tracking)) {
    return false;
  }
  if (!showVisibility || visibility === "all") {
    return true;
  }
  if (visibility === "public") {
    return repo.visibility === "PUBLIC";
  }
  if (visibility === "private") {
    return repo.visibility === "PRIVATE";
  }
  return repo.visibility === "INTERNAL";
}
