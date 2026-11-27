import { repository } from "./fixtures";

/** Canonical in-app base for the shared fixture repository. */
export const repoBase = `/${repository.owner.login}/${repository.name}`;

export function publicResolveResponse(repositoryId: string | number = repository.id) {
  return {
    ownerId: String(repository.owner.id),
    repositoryId: String(repositoryId),
    canonicalPath: repoBase,
    redirect: false,
  };
}

export function isPublicResolve(url: string) {
  return String(url).includes("/api/v1/public/resolve");
}

/** Empty catalog page — resolve falls through to public /resolve or admin list. */
export const emptyCursorPage = { items: [] as never[], nextCursor: null as string | null };

export function isOwnersCatalog(url: string) {
  return String(url).includes("/api/v1/owners");
}
