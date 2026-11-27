import { api } from "./api";

export type CursorPage<T> = {
  items: T[];
  nextCursor: string | null;
};

export type OwnerCard = {
  ownerId: string;
  login: string;
};

export type CatalogRepositoryCard = {
  repositoryId: string;
  ownerId: string;
  name: string;
  fullName: string;
  description: string | null;
  language: string | null;
  archived: boolean;
  fork: boolean;
  activityStatus: string;
  modules: {
    dashboard: boolean;
    traffic: boolean;
    referrers: boolean;
    paths: boolean;
    topics: boolean;
  };
};

/** Exact login lookup for F5 / deep links without localStorage. */
export async function findOwnerByLogin(login: string): Promise<OwnerCard | null> {
  const page = await api<CursorPage<OwnerCard>>(
    `/api/v1/owners?login=${encodeURIComponent(login)}&limit=1`,
  );
  return page.items[0] ?? null;
}

/** Exact repository name under an owner id. */
export async function findRepositoryByName(
  ownerId: string,
  name: string,
): Promise<CatalogRepositoryCard | null> {
  const page = await api<CursorPage<CatalogRepositoryCard>>(
    `/api/v1/owners/${encodeURIComponent(ownerId)}/repositories?name=${encodeURIComponent(name)}&limit=1`,
  );
  return page.items[0] ?? null;
}
