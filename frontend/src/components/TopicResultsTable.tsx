import { useMemo } from "react";
import { type TopicRunResults } from "../lib/api";
import { cn, formatNumber, formatPositionDelta } from "../lib/utils";
import { useTableSort } from "../lib/tableSortPrefs";

type ResultRow = TopicRunResults["rows"][number];

const SORT_KEYS = ["position", "fullName", "stars", "forks", "language", "delta"] as const;
const ASC_FIRST_KEYS = ["position", "fullName", "language"] as const;

type SortKey = (typeof SORT_KEYS)[number];

export function TopicResultsTable({
  rows,
  repositoryId,
  trackedGithubId,
}: {
  rows: ResultRow[];
  repositoryId: number;
  trackedGithubId?: number;
}) {
  const { sortKey, sortDir, toggle } = useTableSort({
    tableId: "topic-results",
    scope: repositoryId,
    keys: SORT_KEYS,
    initial: { key: null, dir: "desc" },
    ascFirst: ASC_FIRST_KEYS,
  });

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
      <table className="w-full min-w-[640px] table-fixed text-sm">
        <thead>
          <tr className="text-left text-muted-foreground">
            <SortHeader className="w-10" label="#" align="left" active={sortKey === "position"} dir={sortDir} onClick={() => toggle("position")} />
            <SortHeader label="Repository" align="left" active={sortKey === "fullName"} dir={sortDir} onClick={() => toggle("fullName")} />
            <SortHeader className="w-[7.5rem]" label="Stars" active={sortKey === "stars"} dir={sortDir} onClick={() => toggle("stars")} />
            <SortHeader className="w-[7.5rem]" label="Forks" active={sortKey === "forks"} dir={sortDir} onClick={() => toggle("forks")} />
            <SortHeader className="w-[8rem]" label="Language" align="left" active={sortKey === "language"} dir={sortDir} onClick={() => toggle("language")} />
            <SortHeader className="w-20" label="Δ" active={sortKey === "delta"} dir={sortDir} onClick={() => toggle("delta")} />
          </tr>
        </thead>
        <tbody>
          {sorted.map((row) => {
            const mine = trackedGithubId != null && row.result.githubRepositoryId === trackedGithubId;
            return (
              <tr key={row.result.githubRepositoryId} className={`border-t ${mine ? "bg-blue-50 font-medium" : ""}`}>
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
                <td className="whitespace-nowrap px-4 py-2.5 text-right align-top">{formatNumber(row.result.forks)}</td>
                <td className="whitespace-nowrap px-4 py-2.5 align-top">{row.result.language ?? "—"}</td>
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
      <button type="button" className={cn("font-medium hover:text-foreground", active && "text-foreground")} onClick={onClick}>
        {label}
        {active ? (dir === "desc" ? " ↓" : " ↑") : ""}
      </button>
    </th>
  );
}

function compareRows(left: ResultRow, right: ResultRow, key: SortKey, dir: "asc" | "desc"): number {
  if (key === "fullName" || key === "language") {
    const leftValue = key === "fullName" ? left.result.fullName : left.result.language ?? "";
    const rightValue = key === "fullName" ? right.result.fullName : right.result.language ?? "";
    const compared = leftValue.localeCompare(rightValue, undefined, { sensitivity: "base" });
    return dir === "desc" ? -compared : compared;
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
