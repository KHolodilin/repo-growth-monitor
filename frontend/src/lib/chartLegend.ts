import { publicContext, readPublicPreference, writePublicPreference } from "./publicPrefs";
import { readJsonCookie, writeJsonCookie } from "./cookies";

const COOKIE = "rgm-chart-legend";

export const TRAFFIC_SERIES = [
  { key: "Views", name: "Views" },
  { key: "Visitors", name: "Visitors" },
  { key: "Clones", name: "Clones" },
  { key: "Unique Cloners", name: "Unique Cloners" },
] as const;

export const REPO_TRAFFIC_SERIES = [
  { key: "Views", name: "Views" },
  { key: "Visitors", name: "Visitors" },
  { key: "Total Clones", name: "Clones" },
  { key: "Clones", name: "Unique Cloners" },
] as const;

export function dashboardTrafficChartId() {
  return "dashboard:traffic";
}

export function repoTrafficChartId(repoId: number | string) {
  return `${repoId}:traffic`;
}

export function repoSearchChartId(repoId: number | string) {
  return `${repoId}:search`;
}

export function repoTopicsChartId(repoId: number | string, scope: string) {
  return `${repoId}:topics:${scope}`;
}

export const STATS_SERIES = [
  { key: "Stars", name: "Stars" },
  { key: "Forks", name: "Forks" },
  { key: "Watchers", name: "Watchers" },
  { key: "Contributors", name: "Contributors" },
] as const;

export const DASHBOARD_STATS_SERIES = [
  { key: "Stars", name: "Stars" },
  { key: "Forks", name: "Forks" },
  { key: "Watchers", name: "Watchers" },
  { key: "Unique Contributors", name: "Unique Contributors" },
] as const;

export function repoStatsChartId(repoId: number | string) {
  return `${repoId}:stats`;
}

export function dashboardStatsChartId() {
  return "dashboard:stats";
}

function parseAll(parsed: unknown): Record<string, Record<string, boolean>> | null {
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
    return null;
  }
  const result: Record<string, Record<string, boolean>> = {};
  for (const [chartId, value] of Object.entries(parsed)) {
    if (!value || typeof value !== "object" || Array.isArray(value)) {
      continue;
    }
    const selected: Record<string, boolean> = {};
    for (const [key, flag] of Object.entries(value)) {
      if (typeof flag === "boolean") {
        selected[key] = flag;
      }
    }
    result[chartId] = selected;
  }
  return result;
}

function readAll(): Record<string, Record<string, boolean>> {
  return readJsonCookie(COOKIE, parseAll) ?? {};
}

export function readChartSelection(chartId: string): Record<string, boolean> | null {
  if (publicContext(chartId)) return readPublicPreference(chartId, "legend", (v): v is Record<string, boolean> => Boolean(v && typeof v === "object" && Object.values(v).every(item => typeof item === "boolean"))) ?? null;
  const stored = readAll()[chartId];
  return stored && Object.keys(stored).length > 0 ? stored : null;
}

export function writeChartSelection(chartId: string, selected: Record<string, boolean>) {
  if (publicContext(chartId)) { writePublicPreference(chartId, "legend", selected); return; }
  const all = readAll();
  const cleaned: Record<string, boolean> = {};
  for (const [key, flag] of Object.entries(selected)) {
    cleaned[key] = flag;
  }
  if (Object.keys(cleaned).length === 0) {
    delete all[chartId];
  } else {
    all[chartId] = cleaned;
  }
  writeJsonCookie(COOKIE, all);
}

export function pruneChartSelection(chartId: string, remainingKeys: string[]) {
  const stored = readChartSelection(chartId);
  if (!stored) {
    return;
  }
  const remaining = new Set(remainingKeys);
  const next: Record<string, boolean> = {};
  for (const [key, flag] of Object.entries(stored)) {
    if (remaining.has(key)) {
      next[key] = flag;
    }
  }
  writeChartSelection(chartId, next);
}

/** Prefer Visitors / Unique Visitors when no legend cookie is stored. */
export function defaultChartSelection(keys: string[]): Record<string, boolean> {
  const visitors = keys.find((key) => key === "Visitors" || key === "Unique Visitors");
  const selected: Record<string, boolean> = {};
  for (const key of keys) {
    selected[key] = visitors ? key === visitors : true;
  }
  return selected;
}

export function resolveChartSelection(
  chartId: string,
  keys: string[],
): Record<string, boolean> {
  const stored = readChartSelection(chartId);
  if (!stored) {
    return defaultChartSelection(keys);
  }
  const selected: Record<string, boolean> = {};
  for (const key of keys) {
    selected[key] = Object.prototype.hasOwnProperty.call(stored, key) ? stored[key] : true;
  }
  return selected;
}

export function isVisitorsSeriesName(name: string): boolean {
  return name === "Visitors" || name === "Unique Visitors";
}

export function defaultMobileSeriesName(options: readonly { name: string }[]): string | null {
  if (options.length === 0) {
    return null;
  }
  return options.find((item) => isVisitorsSeriesName(item.name))?.name ?? options[0].name;
}
