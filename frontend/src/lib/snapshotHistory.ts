import type { SnapshotHistoryCell, SnapshotHistoryRow } from "./api";
import type { SnapshotMetric } from "./snapshotChartPrefs";

export const OTHER_KEY = "Other";

export function cellValue(cell: SnapshotHistoryCell, metric: SnapshotMetric) {
  return metric === "VISITORS" ? cell.visitors : cell.views;
}

function cellDelta(cell: SnapshotHistoryCell, metric: SnapshotMetric) {
  return metric === "VISITORS" ? cell.visitorsDelta : cell.viewsDelta;
}

/**
 * The chart keeps the meaning it had before the history page existed: it plots what the rolling
 * window gained since the previous snapshot. A shrinking window means GitHub dropped older days,
 * not that the source lost visitors, so such a point is left empty instead of drawn as a drop.
 */
export function chartValue(cell: SnapshotHistoryCell, metric: SnapshotMetric) {
  const value = cellValue(cell, metric);
  if (value === null) {
    return null;
  }
  if (cell.firstSeen) {
    return value;
  }
  const delta = cellDelta(cell, metric);
  if (delta === null || delta < 0) {
    return null;
  }
  return delta;
}

/** Rows keep their place while they are missing from the newest snapshot, so look back for a value. */
export function latestValue(row: SnapshotHistoryRow, metric: SnapshotMetric) {
  for (let index = row.cells.length - 1; index >= 0; index--) {
    const value = cellValue(row.cells[index], metric);
    if (value !== null) {
      return value;
    }
  }
  return 0;
}

export function defaultTopKeys(rows: SnapshotHistoryRow[], metric: SnapshotMetric, limit: number) {
  const other: SnapshotMetric = metric === "VISITORS" ? "VIEWS" : "VISITORS";
  return [...rows]
    .sort(
      (left, right) =>
        latestValue(right, metric) - latestValue(left, metric)
        || latestValue(right, other) - latestValue(left, other)
        || left.key.localeCompare(right.key),
    )
    .slice(0, limit)
    .map((row) => row.key);
}

export function formatSnapshotDelta(cell: SnapshotHistoryCell, metric: SnapshotMetric) {
  if (cellValue(cell, metric) === null) {
    return null;
  }
  if (cell.firstSeen) {
    return { label: "N", direction: "up" as const };
  }
  const delta = cellDelta(cell, metric);
  if (delta === null || delta === 0) {
    return null;
  }
  if (delta > 0) {
    return { label: `↑${delta}`, direction: "up" as const };
  }
  return { label: `↓${Math.abs(delta)}`, direction: "down" as const };
}
