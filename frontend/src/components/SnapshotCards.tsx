import { useMemo } from "react";
import { Link } from "react-router-dom";
import { cn, formatNumber, formatSyncTime } from "../lib/utils";
import { useTableSort, type TableId } from "../lib/tableSortPrefs";
import { ReferrerSourceIcon } from "./ReferrerSourceIcon";
import { Card } from "./ui";

const TOP_ROWS = 5;
const SORT_KEYS = ["name", "visitors", "views"] as const;
const ASC_FIRST_KEYS = ["name"] as const;

type SortKey = (typeof SORT_KEYS)[number];
type CardRow = { key: string; title: string; subtitle?: string; visitors: number; views: number };

export function SnapshotCards({
  repositoryId,
  referrers,
  referrerSnapshotAt,
  paths,
  pathSnapshotAt,
}: {
  repositoryId: number;
  referrers: { referrer: string; views: number; uniqueVisitors: number }[];
  referrerSnapshotAt?: string;
  paths: { path: string; title?: string; views: number; uniqueVisitors: number }[];
  pathSnapshotAt?: string;
}) {
  const referrerRows = referrers.map((row) => ({
    key: row.referrer,
    title: row.referrer,
    visitors: row.uniqueVisitors,
    views: row.views,
  }));
  const pathRows = paths.map((row) => ({
    key: row.path,
    title: row.path,
    subtitle: row.title,
    visitors: row.uniqueVisitors,
    views: row.views,
  }));

  return (
    <div className="grid gap-4 md:grid-cols-2">
      <SnapshotCard
        tableId="top-referrers"
        repositoryId={repositoryId}
        title="Top Referrers"
        firstColumn="Source"
        rows={referrerRows}
        snapshotAt={referrerSnapshotAt}
        icons
        historyTo={`/repositories/${repositoryId}/traffic/history?kind=referrers`}
      />
      <SnapshotCard
        tableId="popular-paths"
        repositoryId={repositoryId}
        title="Popular Paths"
        firstColumn="Path"
        rows={pathRows}
        snapshotAt={pathSnapshotAt}
        historyTo={`/repositories/${repositoryId}/traffic/history?kind=paths`}
      />
    </div>
  );
}

function SnapshotCard({
  tableId,
  repositoryId,
  title,
  firstColumn,
  rows,
  snapshotAt,
  historyTo,
  icons = false,
}: {
  tableId: TableId;
  repositoryId: number;
  title: string;
  firstColumn: string;
  rows: CardRow[];
  snapshotAt?: string;
  historyTo: string;
  icons?: boolean;
}) {
  const { sortKey, sortDir, toggle } = useTableSort({
    tableId,
    scope: repositoryId,
    keys: SORT_KEYS,
    initial: { key: "views", dir: "desc" },
    ascFirst: ASC_FIRST_KEYS,
  });

  const sorted = useMemo(() => {
    const copy = [...rows];
    copy.sort((left, right) => {
      const compared = compareCardRows(left, right, sortKey, sortDir);
      if (compared !== 0) {
        return compared;
      }
      return left.key.localeCompare(right.key);
    });
    return copy.slice(0, TOP_ROWS);
  }, [rows, sortKey, sortDir]);

  return (
    <Card>
      <div className="mb-1 flex items-center justify-between gap-3">
        <h2 className="font-medium">{title}</h2>
        <Link className="text-sm font-medium text-primary" to={historyTo}>
          History →
        </Link>
      </div>
      <div className="mb-3 text-xs text-muted-foreground">
        {snapshotAt ? `Snapshot: ${formatSyncTime(snapshotAt)}` : "No snapshot yet"}
      </div>
      <div className="overflow-x-auto">
        <table className="w-full table-fixed text-sm">
          <thead>
            <tr className="text-left text-muted-foreground">
              <SortHeader
                label={firstColumn}
                align="left"
                className="pr-3"
                active={sortKey === "name"}
                dir={sortDir}
                onClick={() => toggle("name")}
              />
              <SortHeader
                label="Visitors"
                className="w-[4.75rem] whitespace-nowrap pl-2"
                active={sortKey === "visitors"}
                dir={sortDir}
                onClick={() => toggle("visitors")}
              />
              <SortHeader
                label="Views"
                className="w-16 whitespace-nowrap pl-2"
                active={sortKey === "views"}
                dir={sortDir}
                onClick={() => toggle("views")}
              />
            </tr>
          </thead>
          <tbody>
            {sorted.map((row) => (
              <tr key={row.key} className="border-t">
                <td className="max-w-0 py-2 pr-3 align-top">
                  <div className="flex items-start gap-2">
                    {icons && <ReferrerSourceIcon source={row.title} />}
                    <div>
                      <div className="[overflow-wrap:anywhere]">{wrapPath(row.title)}</div>
                      {row.subtitle && <div className="text-xs text-muted-foreground">{row.subtitle}</div>}
                    </div>
                  </div>
                </td>
                <td className="whitespace-nowrap pl-2 text-right align-top">{formatNumber(row.visitors)}</td>
                <td className="whitespace-nowrap pl-2 text-right align-top">{formatNumber(row.views)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </Card>
  );
}

function SortHeader({
  label,
  active,
  dir,
  align = "right",
  className,
  onClick,
}: {
  label: string;
  active: boolean;
  dir: "asc" | "desc";
  align?: "left" | "right";
  className?: string;
  onClick: () => void;
}) {
  return (
    <th className={cn(align === "right" && "text-right", className)}>
      <button
        type="button"
        className={cn("font-medium hover:text-foreground", active && "text-foreground")}
        onClick={onClick}
      >
        {label}
        {active ? (dir === "desc" ? " ↓" : " ↑") : ""}
      </button>
    </th>
  );
}

function compareCardRows(left: CardRow, right: CardRow, key: SortKey, dir: "asc" | "desc") {
  const sign = dir === "desc" ? -1 : 1;
  if (key === "name") {
    return sign * left.title.localeCompare(right.title, undefined, { sensitivity: "base" });
  }
  return sign * (left[key] - right[key]);
}

function wrapPath(value: string) {
  return value.replaceAll("/", "/\u200b");
}
