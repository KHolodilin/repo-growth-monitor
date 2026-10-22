const MAX_AGE = 60 * 60 * 24 * 365;
const DEFAULT_COUNT = 5;

export function searchQuerySelectionCookie(repositoryId: number) {
  return `search_visibility_queries_${repositoryId}`;
}

export function readSearchQuerySelection(repositoryId: number): number[] | null {
  const name = searchQuerySelectionCookie(repositoryId);
  const match = document.cookie.match(new RegExp(`(?:^|; )${name}=([^;]*)`));
  if (!match) {
    return null;
  }
  try {
    const parsed = JSON.parse(decodeURIComponent(match[1])) as unknown;
    if (!Array.isArray(parsed) || parsed.some((item) => typeof item !== "number" || !Number.isInteger(item))) {
      return null;
    }
    return parsed;
  } catch {
    return null;
  }
}

export function writeSearchQuerySelection(repositoryId: number, ids: number[], protocol = currentProtocol()) {
  const secure = protocol === "https:" ? "; Secure" : "";
  const value = encodeURIComponent(JSON.stringify(ids));
  document.cookie = `${searchQuerySelectionCookie(repositoryId)}=${value}; path=/; max-age=${MAX_AGE}; SameSite=Lax${secure}`;
}

function currentProtocol() {
  return location.protocol;
}

export function defaultSearchQueryIds(queries: { id: number; rank: number | null }[]) {
  return [...queries]
    .sort((left, right) => {
      if (left.rank == null && right.rank == null) {
        return left.id - right.id;
      }
      if (left.rank == null) {
        return 1;
      }
      if (right.rank == null) {
        return -1;
      }
      if (left.rank !== right.rank) {
        return left.rank - right.rank;
      }
      return left.id - right.id;
    })
    .slice(0, DEFAULT_COUNT)
    .map((query) => query.id);
}

/** A saved cookie, including an empty one, wins. A missing cookie selects the five best ranks. */
export function resolveSearchQuerySelection(
  repositoryId: number,
  queries: { id: number; rank: number | null }[],
) {
  const known = new Set(queries.map((query) => query.id));
  const stored = readSearchQuerySelection(repositoryId);
  if (stored) {
    const next = stored.filter((id) => known.has(id));
    if (next.length !== stored.length) {
      writeSearchQuerySelection(repositoryId, next);
    }
    return next;
  }
  const next = defaultSearchQueryIds(queries);
  writeSearchQuerySelection(repositoryId, next);
  return next;
}
