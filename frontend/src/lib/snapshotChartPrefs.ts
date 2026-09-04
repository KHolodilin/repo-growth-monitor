const PREFIX = "repo-growth.snapshot-chart.";
const MAX_AGE = 60 * 60 * 24 * 365;

export type SnapshotMetric = "VISITORS" | "VIEWS";

export type SnapshotChartKind = "referrers" | "paths";

export type SnapshotChartPrefs = {
  metric: SnapshotMetric;
  keys: string[];
};

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

/** Referrers and paths are two independent charts, so their selections must not overwrite each other. */
function cookieName(repositoryId: number | string, kind: SnapshotChartKind) {
  return `${PREFIX}${kind}.${repositoryId}`;
}

export function readSnapshotChartPrefs(
  repositoryId: number | string,
  kind: SnapshotChartKind,
): SnapshotChartPrefs | null {
  const raw = readCookie(cookieName(repositoryId, kind));
  if (!raw) {
    return null;
  }
  try {
    const parsed: unknown = JSON.parse(raw);
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
      return null;
    }
    const record = parsed as { metric?: unknown; keys?: unknown };
    const metric = record.metric === "VIEWS" ? "VIEWS" : record.metric === "VISITORS" ? "VISITORS" : null;
    if (!metric || !Array.isArray(record.keys)) {
      return null;
    }
    const keys = record.keys.filter((item): item is string => typeof item === "string" && item.length > 0);
    return { metric, keys };
  } catch {
    return null;
  }
}

export function writeSnapshotChartPrefs(
  repositoryId: number | string,
  kind: SnapshotChartKind,
  prefs: SnapshotChartPrefs,
) {
  writeCookie(cookieName(repositoryId, kind), JSON.stringify(prefs));
}
