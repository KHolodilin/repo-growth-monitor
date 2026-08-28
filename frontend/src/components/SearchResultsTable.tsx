import { useMemo, useState } from "react";
import { type SearchRunResults } from "../lib/api";
import {
  activityClass,
  cn,
  formatActivityPresentation,
  formatNumber,
  formatPositionDelta,
  formatSyncTime,
} from "../lib/utils";

type ResultRow = SearchRunResults["rows"][number];
type SortKey = "position" | "fullName" | "stars" | "watchers" | "forks" | "contributors" | "activity" | "delta";

const STATUS_RANK: Record<string, number> = {
  ACTIVE: 3,
  LOW_ACTIVITY: 2,
  INACTIVE: 1,
  UNKNOWN: 0,
};

export function SearchResultsTable({
  rows,
  trackedGithubId,
  trackedPosition,
}: {
  rows: ResultRow[];
  trackedGithubId?: number;
  trackedPosition?: number | null;
}) {
  const [sortKey, setSortKey] = useState<SortKey | null>(null);
  const [sortDir, setSortDir] = useState<"asc" | "desc">("desc");

  function toggle(key: SortKey) {
    if (sortKey === key) {
      setSortDir((current) => (current === "desc" ? "asc" : "desc"));
      return;
    }
    setSortKey(key);
    setSortDir(key === "fullName" || key === "position" ? "asc" : "desc");
  }

  const sorted = useMemo(() => {
    if (!sortKey) {
      return rows;
    }
    const copy = [...rows];
    copy.sort((left, right) => {
      const compared = compareRows(left, right, sortKey, sortDir);
      if (compared !== 0) {
        return compared;
      }
      return left.result.position - right.result.position;
    });
    return copy;
  }, [rows, sortKey, sortDir]);

  return (
    <div className="overflow-x-auto">
      <table className="w-full min-w-[760px] table-fixed text-sm">
        <thead>
          <tr className="text-left text-muted-foreground">
            <SortHeader
              className="w-10"
              label="#"
              align="left"
              active={sortKey === "position"}
              dir={sortDir}
              onClick={() => toggle("position")}
            />
            <SortHeader
              label="Repository"
              align="left"
              active={sortKey === "fullName"}
              dir={sortDir}
              onClick={() => toggle("fullName")}
            />
            <SortHeader
              className="w-[7.5rem]"
              label="Stars"
              active={sortKey === "stars"}
              dir={sortDir}
              onClick={() => toggle("stars")}
            />
            <SortHeader
              className="w-[7.5rem]"
              label="Watchers"
              active={sortKey === "watchers"}
              dir={sortDir}
              onClick={() => toggle("watchers")}
            />
            <SortHeader
              className="w-[7.5rem]"
              label="Forks"
              active={sortKey === "forks"}
              dir={sortDir}
              onClick={() => toggle("forks")}
            />
            <SortHeader
              className="w-[8.5rem]"
              label="Contributors"
              active={sortKey === "contributors"}
              dir={sortDir}
              onClick={() => toggle("contributors")}
            />
            <SortHeader
              className="w-[10rem]"
              label="Activity"
              align="left"
              active={sortKey === "activity"}
              dir={sortDir}
              onClick={() => toggle("activity")}
            />
            <SortHeader
              className="w-20"
              label="Δ"
              active={sortKey === "delta"}
              dir={sortDir}
              onClick={() => toggle("delta")}
            />
          </tr>
        </thead>
        <tbody>
          {sorted.map((row) => {
            const mine =
              trackedGithubId != null
                ? row.result.githubRepositoryId === trackedGithubId
                : trackedPosition != null && row.result.position === trackedPosition;
            return (
              <tr
                key={row.result.githubRepositoryId}
                className={`border-t ${mine ? "bg-blue-50 font-medium" : ""}`}
                title={
                  row.result.metadataUpdatedAt
                    ? `Repository metadata updated: ${formatSyncTime(row.result.metadataUpdatedAt)}`
                    : undefined
                }
              >
                <td className="whitespace-nowrap px-4 py-2.5 align-top">{row.result.position}</td>
                <td className="max-w-0 px-4 py-2.5 align-top">
                  <a
                    className="break-words [overflow-wrap:anywhere] text-primary hover:underline"
                    href={row.result.htmlUrl ?? `https://github.com/${row.result.fullName}`}
                    target="_blank"
                    rel="noreferrer"
                  >
                    {row.result.fullName}
                  </a>
                </td>
                <td className="whitespace-nowrap px-4 py-2.5 text-right align-top">{formatNumber(row.result.stars)}</td>
                <td className="whitespace-nowrap px-4 py-2.5 text-right align-top">{formatNumber(row.result.watchers)}</td>
                <td className="whitespace-nowrap px-4 py-2.5 text-right align-top">{formatNumber(row.result.forks)}</td>
                <td className="whitespace-nowrap px-4 py-2.5 text-right align-top">{formatNumber(row.result.contributors)}</td>
                <td className={activityClass(row.result.activityStatus) + " whitespace-nowrap px-4 py-2.5 align-top"}>
                  {formatActivityPresentation(row.result.activityStatus, row.result.activityAt)}
                </td>
                <td className="whitespace-nowrap px-4 py-2.5 text-right align-top">{formatPositionDelta(row.positionDelta)}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
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
    <th className={cn("whitespace-nowrap px-4 py-2", align === "right" && "text-right", className)}>
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

function compareRows(left: ResultRow, right: ResultRow, key: SortKey, dir: "asc" | "desc"): number {
  if (key === "fullName") {
    const compared = left.result.fullName.localeCompare(right.result.fullName, undefined, { sensitivity: "base" });
    return dir === "desc" ? -compared : compared;
  }
  if (key === "activity") {
    const byTime = compareNumbers(
      left.result.activityAt ? Date.parse(left.result.activityAt) : null,
      right.result.activityAt ? Date.parse(right.result.activityAt) : null,
      dir,
    );
    if (byTime !== 0) {
      return byTime;
    }
    return compareNumbers(
      STATUS_RANK[left.result.activityStatus ?? "UNKNOWN"] ?? 0,
      STATUS_RANK[right.result.activityStatus ?? "UNKNOWN"] ?? 0,
      dir,
    );
  }
  if (key === "delta") {
    return compareNumbers(left.positionDelta, right.positionDelta, dir);
  }
  if (key === "position") {
    return compareNumbers(left.result.position, right.result.position, dir);
  }
  return compareNumbers(left.result[key], right.result[key], dir);
}

function compareNumbers(left: number | null, right: number | null, dir: "asc" | "desc"): number {
  if (left === null && right === null) {
    return 0;
  }
  if (left === null) {
    return 1;
  }
  if (right === null) {
    return -1;
  }
  return dir === "desc" ? right - left : left - right;
}
