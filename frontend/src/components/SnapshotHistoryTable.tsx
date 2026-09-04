import { Fragment } from "react";
import type { SnapshotHistory, SnapshotHistoryCell } from "../lib/api";
import { cellValue, formatSnapshotDelta } from "../lib/snapshotHistory";
import type { SnapshotChartKind, SnapshotMetric } from "../lib/snapshotChartPrefs";
import { cn, formatChartAxisDate, formatNumber, growthClass } from "../lib/utils";
import { ReferrerSourceIcon } from "./ReferrerSourceIcon";
import { Card } from "./ui";

export function SnapshotHistoryTable({
  kind,
  history,
}: {
  kind: SnapshotChartKind;
  history: SnapshotHistory;
}) {
  const firstColumn = kind === "referrers" ? "Source" : "Path";
  const showIcons = kind === "referrers";

  if (history.dates.length === 0) {
    return (
      <Card>
        <h2 className="mb-3 font-medium">Snapshots</h2>
        <div className="rounded-lg border border-dashed px-4 py-10 text-center text-sm text-muted-foreground">
          <div className="font-medium text-foreground">No snapshots for this period.</div>
          <div className="mt-1">Collect the repository to store the first snapshot.</div>
        </div>
      </Card>
    );
  }

  return (
    <Card>
      <div className="mb-3 flex flex-wrap items-center justify-between gap-3">
        <h2 className="font-medium">Snapshots</h2>
        <span className="text-sm text-muted-foreground">
          Values as reported by GitHub, with the change from the previous snapshot
        </span>
      </div>
      <div className="max-h-[70vh] overflow-auto">
        <table className="min-w-full border-separate border-spacing-0 text-sm">
          <thead>
            <tr>
              <th
                rowSpan={2}
                className="sticky left-0 top-0 z-30 border-b bg-card px-3 pb-2 text-left align-bottom font-medium text-muted-foreground"
              >
                {firstColumn}
              </th>
              {history.dates.map((date) => (
                <th
                  key={date}
                  colSpan={2}
                  className="sticky top-0 z-20 h-8 whitespace-nowrap border-b border-l bg-card px-2 text-center text-xs font-medium text-muted-foreground"
                >
                  {formatChartAxisDate(date)}
                </th>
              ))}
            </tr>
            <tr>
              {history.dates.map((date) => (
                <Fragment key={date}>
                  <th className="sticky top-8 z-20 whitespace-nowrap border-b border-l bg-card px-2 pb-2 text-right text-xs font-normal text-muted-foreground">
                    Visitors
                  </th>
                  <th className="sticky top-8 z-20 whitespace-nowrap border-b bg-card px-2 pb-2 text-right text-xs font-normal text-muted-foreground">
                    Views
                  </th>
                </Fragment>
              ))}
            </tr>
          </thead>
          <tbody>
            {history.rows.map((row) => (
              <tr key={row.key}>
                <td className="sticky left-0 z-10 max-w-[18rem] border-b bg-card px-3 py-2 align-top">
                  <div className="flex items-start gap-2">
                    {showIcons && <ReferrerSourceIcon source={row.key} />}
                    <div className="min-w-0">
                      <div className="[overflow-wrap:anywhere]">{wrapPath(row.key)}</div>
                      {row.title && <div className="text-xs text-muted-foreground">{row.title}</div>}
                    </div>
                  </div>
                </td>
                {row.cells.map((cell) => (
                  <Fragment key={cell.date}>
                    <ValueCell cell={cell} metric="VISITORS" className="border-l" />
                    <ValueCell cell={cell} metric="VIEWS" />
                  </Fragment>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </Card>
  );
}

function ValueCell({
  cell,
  metric,
  className,
}: {
  cell: SnapshotHistoryCell;
  metric: SnapshotMetric;
  className?: string;
}) {
  const value = cellValue(cell, metric);
  const delta = formatSnapshotDelta(cell, metric);
  return (
    <td className={cn("whitespace-nowrap border-b px-2 py-2 text-right align-top", className)}>
      {value === null ? (
        <span className="text-muted-foreground">—</span>
      ) : (
        <span className="inline-flex items-baseline gap-1">
          <span>{formatNumber(value)}</span>
          {delta && <span className={cn("text-xs font-medium", growthClass(delta.direction))}>{delta.label}</span>}
        </span>
      )}
    </td>
  );
}

function wrapPath(value: string) {
  return value.replaceAll("/", "/\u200b");
}
