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
  return path.includes("/api/v1/owners");
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
