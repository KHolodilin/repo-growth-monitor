import { readJsonCookie, writeJsonCookie } from "./cookies";

const PREFIX = "repo-growth.snapshot-chart.";

export type SnapshotMetric = "VISITORS" | "VIEWS";

export type SnapshotChartKind = "referrers" | "paths";

export type SnapshotChartPrefs = {
  metric: SnapshotMetric;
  keys: string[];
};

/** Referrers and paths are two independent charts, so their selections must not overwrite each other. */
function cookieName(repositoryId: number | string, kind: SnapshotChartKind) {
  return `${PREFIX}${kind}.${repositoryId}`;
}

function parsePrefs(value: unknown): SnapshotChartPrefs | null {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    return null;
  }
  const record = value as { metric?: unknown; keys?: unknown };
  const metric = record.metric === "VIEWS" ? "VIEWS" : record.metric === "VISITORS" ? "VISITORS" : null;
  if (!metric || !Array.isArray(record.keys)) {
    return null;
  }
  const keys = record.keys.filter((item): item is string => typeof item === "string" && item.length > 0);
  return { metric, keys };
}

export function readSnapshotChartPrefs(
  repositoryId: number | string,
  kind: SnapshotChartKind,
): SnapshotChartPrefs | null {
  return readJsonCookie(cookieName(repositoryId, kind), parsePrefs);
}

export function writeSnapshotChartPrefs(
  repositoryId: number | string,
  kind: SnapshotChartKind,
  prefs: SnapshotChartPrefs,
) {
  writeJsonCookie(cookieName(repositoryId, kind), prefs);
}
