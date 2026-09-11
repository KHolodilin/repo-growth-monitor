import { useState } from "react";
import { readJsonCookie, writeJsonCookie } from "./cookies";

const PREFIX = "repo-growth.table-sort.";
const GLOBAL_SCOPE = "global";

export type SortDirection = "asc" | "desc";

/** Every table owns a cookie so the tables of one page cannot overwrite each other. */
export type TableId =
  | "dashboard-repositories"
  | "search-queries"
  | "search-results"
  | "top-referrers"
  | "popular-paths";

export type TableSort<K extends string> = {
  key: K;
  dir: SortDirection;
};

type StoredSort = {
  key: string;
  dir: SortDirection;
};

function cookieName(tableId: TableId) {
  return `${PREFIX}${tableId}`;
}

/** Repositories are many and cookies are few, so one table keeps all its scopes in a single cookie. */
function scopeKey(scope: number | string | undefined) {
  return scope === undefined || scope === "" ? GLOBAL_SCOPE : String(scope);
}

function parseScopes(value: unknown): Record<string, StoredSort> | null {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    return null;
  }
  const scopes: Record<string, StoredSort> = {};
  for (const [scope, stored] of Object.entries(value)) {
    if (!stored || typeof stored !== "object" || Array.isArray(stored)) {
      continue;
    }
    const record = stored as { key?: unknown; dir?: unknown };
    if (typeof record.key !== "string" || (record.dir !== "asc" && record.dir !== "desc")) {
      continue;
    }
    scopes[scope] = { key: record.key, dir: record.dir };
  }
  return scopes;
}

function readScopes(tableId: TableId): Record<string, StoredSort> {
  return readJsonCookie(cookieName(tableId), parseScopes) ?? {};
}

export function readTableSort<K extends string>(
  tableId: TableId,
  scope: number | string | undefined,
  keys: readonly K[],
): TableSort<K> | null {
  const stored = readScopes(tableId)[scopeKey(scope)];
  if (!stored || !(keys as readonly string[]).includes(stored.key)) {
    return null;
  }
  return { key: stored.key as K, dir: stored.dir };
}

export function writeTableSort<K extends string>(
  tableId: TableId,
  scope: number | string | undefined,
  sort: TableSort<K>,
) {
  const scopes = readScopes(tableId);
  scopes[scopeKey(scope)] = { key: sort.key, dir: sort.dir };
  writeJsonCookie(cookieName(tableId), scopes);
}

/**
 * Restores the stored order before the first render, so a remembered table never shows up
 * in the default order first. `initial.key` may be `null` for tables that open unsorted.
 */
export function useTableSort<K extends string, D extends K | null>({
  tableId,
  scope,
  keys,
  initial,
  ascFirst,
}: {
  tableId: TableId;
  scope?: number | string;
  keys: readonly K[];
  initial: { key: D; dir: SortDirection };
  ascFirst?: readonly K[];
}) {
  const [sort, setSort] = useState<{ key: K | D; dir: SortDirection }>(
    () => readTableSort(tableId, scope, keys) ?? initial,
  );
  const [appliedScope, setAppliedScope] = useState(scope);

  if (appliedScope !== scope) {
    setAppliedScope(scope);
    setSort(readTableSort(tableId, scope, keys) ?? initial);
  }

  function toggle(key: K) {
    const next: TableSort<K> =
      sort.key === key
        ? { key, dir: sort.dir === "desc" ? "asc" : "desc" }
        : { key, dir: ascFirst?.includes(key) ? "asc" : "desc" };
    setSort(next);
    writeTableSort(tableId, scope, next);
  }

  return { sortKey: sort.key, sortDir: sort.dir, toggle };
}
