import { api, ApiError, type Repository } from "./api";

type PublicResolve = {
  ownerId: string;
  repositoryId: string | null;
  canonicalPath: string;
  redirect: boolean;
};

/** Resolve /{owner}/{repo} to the tracked repository record (admin list or published resolve). */
export async function resolveRepository(owner: string, repository: string): Promise<Repository> {
  const fullName = `${owner}/${repository}`;
  let listed = false;
  try {
    const list = await api<Repository[]>("/api/v1/admin/repositories?refresh=false");
    listed = true;
    const match = list.find(
      (row) =>
        row.fullName.toLowerCase() === fullName.toLowerCase()
        || (row.owner.login.toLowerCase() === owner.toLowerCase() && row.name.toLowerCase() === repository.toLowerCase()),
    );
    if (match) {
      return match;
    }
  } catch {
    // Anonymous callers cannot list admin repositories; fall through to public resolve.
  }

  if (listed) {
    throw new ApiError(404, "Repository not found");
  }

  const resolved = await api<PublicResolve>(
    `/api/v1/public/resolve?path=${encodeURIComponent(`/${owner}/${repository}`)}`,
  );
  if (!resolved.repositoryId) {
    throw new ApiError(404, "Repository not found");
  }
  return api<Repository>(`/api/v1/admin/repositories/${resolved.repositoryId}`);
}
