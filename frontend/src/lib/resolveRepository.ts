import { api, ApiError, type Repository } from "./api";
import { findOwnerByLogin, findRepositoryByName } from "./catalogApi";

/**
 * Resolve /{owner}/{repo} for F5 / deep links.
 * Prefer catalog exact login/name filters (no localStorage); fall back to the admin list for
 * operators when the catalog miss is expected for unpublished repos.
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
