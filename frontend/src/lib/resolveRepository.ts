import { api, ApiError, type Repository } from "./api";
import { findOwnerByLogin, findRepositoryByName } from "./catalogApi";

type PublicResolve = {
  ownerId: string;
  repositoryId: string | null;
  canonicalPath: string;
  redirect: boolean;
};

/**
 * Resolve /{owner}/{repo} for F5 / deep links.
 * Prefer catalog exact login/name filters (no localStorage); keep public /resolve and admin list
 * as fallbacks until HTML+SPA drop /resolve together.
 */
export async function resolveRepository(owner: string, repository: string): Promise<Repository> {
  const fullName = `${owner}/${repository}`;

  try {
    const ownerCard = await findOwnerByLogin(owner);
    if (ownerCard) {
      const repoCard = await findRepositoryByName(ownerCard.ownerId, repository);
      if (repoCard) {
        return api<Repository>(`/api/v1/admin/repositories/${repoCard.repositoryId}`);
      }
    }
  } catch (err) {
    if (!(err instanceof ApiError && (err.status === 404 || err.status === 401))) {
      throw err;
    }
  }

  try {
    const resolved = await api<PublicResolve>(
      `/api/v1/public/resolve?path=${encodeURIComponent(`/${owner}/${repository}`)}`,
    );
    if (resolved.repositoryId) {
      return api<Repository>(`/api/v1/admin/repositories/${resolved.repositoryId}`);
    }
  } catch (err) {
    if (!(err instanceof ApiError && err.status === 404)) {
      throw err;
    }
  }

  try {
    const list = await api<Repository[]>("/api/v1/admin/repositories?refresh=false");
    const match = list.find(
      (row) =>
        row.fullName.toLowerCase() === fullName.toLowerCase() ||
        (row.owner.login.toLowerCase() === owner.toLowerCase() &&
          row.name.toLowerCase() === repository.toLowerCase()),
    );
    if (match) {
      return match;
    }
  } catch {
    // Anonymous callers cannot list admin repositories.
  }

  throw new ApiError(404, "Repository not found");
}
