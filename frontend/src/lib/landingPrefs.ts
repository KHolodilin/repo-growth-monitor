import { readPublicPreference, writePublicPreference } from "./publicPrefs";

export const LANDING_SCOPE = "landing";
export const OWNER_SORTS = ["owner", "repositories", "stars", "forks", "watchers", "contributors"] as const;
export const OWNER_ORDERS = ["asc", "desc"] as const;
export const OWNER_PAGE_SIZES = [10, 25, 50] as const;

export type OwnerSort = (typeof OWNER_SORTS)[number];
export type OwnerOrder = (typeof OWNER_ORDERS)[number];
export type OwnerPageSize = (typeof OWNER_PAGE_SIZES)[number];

export type LandingDirectoryPrefs = {
  sort: OwnerSort;
  order: OwnerOrder;
  limit: OwnerPageSize;
};

export const DEFAULT_LANDING_DIRECTORY: LandingDirectoryPrefs = {
  sort: "stars",
  order: "desc",
  limit: 25,
};

function isOwnerSort(value: unknown): value is OwnerSort {
  return typeof value === "string" && (OWNER_SORTS as readonly string[]).includes(value);
}

function isOwnerOrder(value: unknown): value is OwnerOrder {
  return value === "asc" || value === "desc";
}

function isOwnerPageSize(value: unknown): value is OwnerPageSize {
  return typeof value === "number" && (OWNER_PAGE_SIZES as readonly number[]).includes(value);
}

function isLandingDirectoryPrefs(value: unknown): value is LandingDirectoryPrefs {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    return false;
  }
  const record = value as { sort?: unknown; order?: unknown; limit?: unknown };
  return isOwnerSort(record.sort) && isOwnerOrder(record.order) && isOwnerPageSize(record.limit);
}

export function readLandingDirectoryPrefs(): LandingDirectoryPrefs {
  return readPublicPreference(LANDING_SCOPE, "directory", isLandingDirectoryPrefs) ?? DEFAULT_LANDING_DIRECTORY;
}

export function writeLandingDirectoryPrefs(prefs: LandingDirectoryPrefs) {
  writePublicPreference(LANDING_SCOPE, "directory", prefs);
}

export function parseOwnerSort(value: string | null): OwnerSort | undefined {
  return isOwnerSort(value) ? value : undefined;
}

export function parseOwnerOrder(value: string | null): OwnerOrder | undefined {
  return isOwnerOrder(value) ? value : undefined;
}

export function parseOwnerPageSize(value: string | null): OwnerPageSize | undefined {
  if (value == null || value === "") {
    return undefined;
  }
  const parsed = Number(value);
  return isOwnerPageSize(parsed) ? parsed : undefined;
}

export function parseOwnerOffset(value: string | null): number {
  if (value == null || value === "") {
    return 0;
  }
  const parsed = Number(value);
  if (!Number.isInteger(parsed) || parsed < 0 || parsed > 10000) {
    return 0;
  }
  return parsed;
}
