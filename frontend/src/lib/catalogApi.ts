import { api, type Repository } from "./api";

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

/**
 * Load readable catalog repositories for the repo switcher (no admin list GET).
 * Cards are thin; synthesise the Repository fields the switcher needs for navigation.
 */
export async function listCatalogRepositoriesForSwitcher(): Promise<Repository[]> {
  const repos: Repository[] = [];
  let ownerCursor: string | null = null;
  do {
    const ownersUrl =
      ownerCursor == null
        ? "/api/v1/owners?limit=100"
        : `/api/v1/owners?limit=100&cursor=${encodeURIComponent(ownerCursor)}`;
    const owners = await api<CursorPage<OwnerCard>>(ownersUrl);
    for (const owner of owners.items) {
      let repoCursor: string | null = null;
      do {
        const reposUrl =
          repoCursor == null
            ? `/api/v1/owners/${encodeURIComponent(owner.ownerId)}/repositories?limit=100`
            : `/api/v1/owners/${encodeURIComponent(owner.ownerId)}/repositories?limit=100&cursor=${encodeURIComponent(repoCursor)}`;
        const page = await api<CursorPage<CatalogRepositoryCard>>(reposUrl);
        for (const card of page.items) {
          if (!card.repositoryId || !card.name) {
            continue;
          }
          repos.push(catalogCardToSwitcherRepository(card, owner.login));
        }
        repoCursor = page.nextCursor;
      } while (repoCursor);
    }
    ownerCursor = owners.nextCursor;
  } while (ownerCursor);
  return repos;
}

function catalogCardToSwitcherRepository(
  card: CatalogRepositoryCard,
  ownerLogin: string,
): Repository {
  return {
    id: Number(card.repositoryId),
    githubId: 0,
    name: card.name,
    fullName: card.fullName,
    description: card.description ?? undefined,
    visibility: "PUBLIC",
    language: card.language ?? undefined,
    fork: card.fork,
    archived: card.archived,
    stars: 0,
    watchers: 0,
    forks: 0,
    openIssues: 0,
    trackingEnabled: true,
    contributors: 0,
    activityStatus: (card.activityStatus as Repository["activityStatus"]) || null,
    owner: {
      id: Number(card.ownerId),
      githubId: 0,
      login: ownerLogin,
      ownerType: "USER",
    },
  };
}
