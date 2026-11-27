import { api, ApiError, type Repository } from "./api";
import { findOwnerByLogin, findRepositoryByName } from "./catalogApi";

/**
 * Resolve /{owner}/{repo} for F5 / deep links via catalog exact login/name filters.
 * Does not use admin list GETs — unpublished/hidden resources stay 404.
 */
export async function resolveRepository(owner: string, repository: string): Promise<Repository> {
  try {
    const ownerCard = await findOwnerByLogin(owner);
    if (ownerCard) {
      const repoCard = await findRepositoryByName(ownerCard.ownerId, repository);
      if (repoCard) {
        return api<Repository>(`/api/v1/repositories/${repoCard.repositoryId}`);
      }
    }
  } catch (err) {
    if (!(err instanceof ApiError && (err.status === 404 || err.status === 401))) {
      throw err;
    }
  }

  throw new ApiError(404, "Repository not found");
}
