import { api, ApiError, type Repository } from "./api";

type PublicResolve = {
  ownerId: string;
  repositoryId: string | null;
  canonicalPath: string;
  redirect: boolean;
};

/** Resolve /{owner}/{repo} via public publication first; admin list only for unpublished tracked repos. */
export async function resolveRepository(owner: string, repository: string): Promise<Repository> {
  const fullName = `${owner}/${repository}`;

  try {
    const resolved = await api<PublicResolve>(
      `/api/v1/public/resolve?path=${encodeURIComponent(`/${owner}/${repository}`)}`,
    );
    if (resolved.repositoryId) {
      return api<Repository>(`/api/v1/admin/repositories/${resolved.repositoryId}`);
    }
  } catch (err) {
    // Not published (or unknown path): admins may still open a tracked private/unpublished repo.
    if (!(err instanceof ApiError && err.status === 404)) {
      throw err;
    }
  }

  try {
    const list = await api<Repository[]>("/api/v1/admin/repositories?refresh=false");
    const match = list.find(
      (row) =>
        row.fullName.toLowerCase() === fullName.toLowerCase()
        || (row.owner.login.toLowerCase() === owner.toLowerCase() && row.name.toLowerCase() === repository.toLowerCase()),
    );
    if (match) {
      return match;
    }
  } catch {
    // Anonymous callers cannot list admin repositories.
  }

  throw new ApiError(404, "Repository not found");
}
