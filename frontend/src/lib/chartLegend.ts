const COOKIE = "rgm-chart-legend";
const MAX_AGE = 60 * 60 * 24 * 365;

export const TRAFFIC_SERIES = [
  { key: "Views", name: "Views" },
  { key: "Visitors", name: "Visitors" },
  { key: "Clones", name: "Clones" },
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

function cookieAttributes() {
  return `path=/; max-age=${MAX_AGE}; SameSite=Lax`;
}

function readCookie(name: string): string | null {
  const match = document.cookie.match(new RegExp(`(?:^|; )${name}=([^;]*)`));
  return match ? decodeURIComponent(match[1]) : null;
}

function writeCookie(name: string, value: string) {
  document.cookie = `${name}=${encodeURIComponent(value)}; ${cookieAttributes()}`;
}

function readAll(): Record<string, Record<string, boolean>> {
  const raw = readCookie(COOKIE);
  if (!raw) {
    return {};
  }
  try {
    const parsed: unknown = JSON.parse(raw);
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
      return {};
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
  } catch {
    return {};
  }
}

export function readChartSelection(chartId: string): Record<string, boolean> | null {
  const stored = readAll()[chartId];
  return stored && Object.keys(stored).length > 0 ? stored : null;
}

export function writeChartSelection(chartId: string, selected: Record<string, boolean>) {
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
  writeCookie(COOKIE, JSON.stringify(all));
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

export function resolveChartSelection(
  chartId: string,
  keys: string[],
): Record<string, boolean> {
  const stored = readChartSelection(chartId);
  const selected: Record<string, boolean> = {};
  for (const key of keys) {
    selected[key] = stored && Object.prototype.hasOwnProperty.call(stored, key) ? stored[key] : true;
  }
  return selected;
}
