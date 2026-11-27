import type { Repository } from "../lib/api";
import { repository } from "./fixtures";

/** Canonical in-app base for the shared fixture repository. */
export const repoBase = `/${repository.owner.login}/${repository.name}`;

/** Empty catalog page — resolve falls through to the admin list. */
export const emptyCursorPage = { items: [] as never[], nextCursor: null as string | null };

export function isOwnersCatalog(url: string) {
  const path = String(url);
  // Exact login/name lookups are handled by respondCatalogResolve.
  if (path.includes("login=") || path.includes("name=")) {
    return false;
  }
  // RepoSwitcher directory pages are handled by respondCatalogSwitcher.
  if (isCatalogSwitcherUrl(path)) {
    return false;
  }
  return path.includes("/api/v1/owners");
}

function isCatalogSwitcherUrl(path: string) {
  if (path.includes("login=") || path.includes("name=")) {
    return false;
  }
  if (/\/api\/v1\/owners\?/.test(path) && path.includes("limit=")) {
    return true;
  }
  return /\/api\/v1\/owners\/[^/]+\/repositories\?/.test(path) && path.includes("limit=");
}

function toSwitcherCard(repo: Repository) {
  return {
    repositoryId: String(repo.id),
    ownerId: String(repo.owner.id),
    name: repo.name,
    fullName: repo.fullName,
    description: repo.description ?? null,
    language: repo.language ?? null,
    archived: repo.archived,
    fork: repo.fork,
    activityStatus: repo.activityStatus ?? "ACTIVE",
    modules: {
      dashboard: true,
      traffic: true,
      referrers: true,
      paths: true,
      topics: true,
    },
  };
}

/**
 * Catalog owners/repos pages used by RepoSwitcher after leaving admin list GET.
 * Pass extra repositories (e.g. id 8 "other") when the test switches between them.
 */
export function respondCatalogSwitcher(url: string, repos: Repository[] = [repository]) {
  const path = String(url);
  if (!isCatalogSwitcherUrl(path)) {
    return null;
  }
  if (/\/api\/v1\/owners\?/.test(path) && !path.includes("/repositories")) {
    const owners = new Map<string, { ownerId: string; login: string }>();
    for (const repo of repos) {
      owners.set(String(repo.owner.id), {
        ownerId: String(repo.owner.id),
        login: repo.owner.login,
      });
    }
    return { items: [...owners.values()], nextCursor: null };
  }
  const ownerMatch = path.match(/\/api\/v1\/owners\/([^/]+)\/repositories\?/);
  if (ownerMatch) {
    const ownerId = ownerMatch[1];
    return {
      items: repos.filter((repo) => String(repo.owner.id) === ownerId).map(toSwitcherCard),
      nextCursor: null,
    };
  }
  return null;
}

/** Catalog login/name lookup used by resolveRepository after /public/resolve removal. */
export function respondCatalogResolve(url: string, repositoryId: string | number = repository.id) {
  const path = String(url);
  if (path.includes("/api/v1/owners?login=")) {
    return {
      items: [{ ownerId: String(repository.owner.id), login: repository.owner.login }],
      nextCursor: null,
    };
  }
  if (path.includes("/repositories?name=")) {
    const requested = new URL(path, "http://local.test").searchParams.get("name") ?? "";
    const other = requested.toLowerCase() === "other";
    return {
      items: [
        {
          repositoryId: String(other ? 8 : repositoryId),
          ownerId: String(repository.owner.id),
          name: other ? "other" : requested || repository.name,
          fullName: other ? "KHolodilin/other" : `${repository.owner.login}/${requested || repository.name}`,
          description: repository.description ?? null,
          language: repository.language ?? null,
          archived: repository.archived,
          fork: repository.fork,
          activityStatus: "ACTIVE",
          modules: {
            dashboard: true,
            traffic: true,
            referrers: true,
            paths: true,
            topics: true,
          },
        },
      ],
      nextCursor: null,
    };
  }
  return null;
}
