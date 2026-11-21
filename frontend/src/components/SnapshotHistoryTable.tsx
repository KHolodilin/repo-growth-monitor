import { useDashboardView } from "../lib/dashboardView";
import { useIsMobileChart } from "../lib/useMediaQuery";
import { ChartSelection } from "./ResultCards";
import { Fragment, useState } from "react";
import type { SnapshotHistory, SnapshotHistoryCell } from "../lib/api";
import { cellValue, formatSnapshotDelta } from "../lib/snapshotHistory";
import type { SnapshotChartKind, SnapshotMetric } from "../lib/snapshotChartPrefs";
import { cn, formatChartAxisDate, formatNumber, growthClass } from "../lib/utils";
import { ReferrerSourceIcon } from "./ReferrerSourceIcon";
import { Card } from "./ui";

/** Auto table layout squeezes the only wrappable column, so the first column carries its own width. */
const FIRST_COLUMN_WIDTH = "w-56";

export function SnapshotHistoryTable({
  kind,
  history,
  selected = [], onToggle, displayLabel = (key:string)=>key,
}: {
  displayLabel?: (key:string)=>string;
  kind: SnapshotChartKind;
  history: SnapshotHistory;
  selected?: string[];
  onToggle?: (key: string) => void;
}) {
  const view=useDashboardView();
  const mobile = useIsMobileChart();
  const [date, setDate] = useState(history.dates.at(-1));
  const currentDate = date && history.dates.includes(date) ? date : history.dates.at(-1);
  const dateIndex = history.dates.indexOf(currentDate!);
  const firstColumn = kind === "referrers" ? "Source" : "Path";
  const showIcons = kind === "referrers";
  const dates = [...history.dates].reverse();

  if (history.dates.length === 0) {
    return (
      <Card>
        <h2 className="mb-3 font-medium">Snapshots</h2>
        <div className="rounded-lg border border-dashed px-4 py-10 text-center text-sm text-muted-foreground">
          <div className="font-medium text-foreground">No snapshots for this period.</div>
          <div className="mt-1">{view.public?"Saved snapshots will appear here when available.":"Collect the repository to store the first snapshot."}</div>
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
      {mobile ? <div className="space-y-3">
        <div className="flex items-end gap-2">
          <button type="button" aria-label="Previous snapshot" className="min-h-11 rounded border px-3" disabled={dateIndex <= 0} onClick={() => setDate(history.dates[dateIndex - 1])}>←</button>
          <label className="min-w-0 flex-1 text-xs text-muted-foreground">Snapshot date<select className="mt-1 block min-h-11 w-full min-w-0 rounded border bg-card px-2 text-sm text-foreground" value={currentDate} onChange={event => setDate(event.target.value)}>{dates.map(item => <option key={item} value={item}>{item}</option>)}</select></label>
          <button type="button" aria-label="Next snapshot" className="min-h-11 rounded border px-3" disabled={dateIndex >= history.dates.length - 1} onClick={() => setDate(history.dates[dateIndex + 1])}>→</button>
        </div>
        {history.rows.map(row => {
          const cell = row.cells.find(item => item.date === currentDate);
          return <article key={row.key} className="min-w-0 rounded-lg border p-3">
            <div className="flex items-start gap-2">{showIcons && <ReferrerSourceIcon source={row.key} />}<div className="min-w-0 [overflow-wrap:anywhere]">{wrapPath(displayLabel(row.key))}{row.title && row.title!==displayLabel(row.key) && <p className="mt-1 text-xs text-muted-foreground">{row.title}</p>}</div></div>
            <dl className="mt-3 grid grid-cols-2 gap-3 text-sm">{(["VISITORS", "VIEWS"] as const).map(metric => {
              const value = cell ? cellValue(cell, metric) : null;
              const delta = cell ? formatSnapshotDelta(cell, metric) : null;
              return <div key={metric}><dt className="text-xs text-muted-foreground">{metric === "VISITORS" ? "Visitors" : "Views"}</dt><dd>{value === null ? "—" : formatNumber(value)} {delta && <span className={cn("text-xs", growthClass(delta.direction))}>{delta.label}</span>}</dd></div>;
            })}</dl>
            {onToggle && <ChartSelection name={displayLabel(row.key)} checked={selected.includes(row.key)} onChange={() => onToggle(row.key)} />}
          </article>;
        })}
      </div> : <div className="max-h-[70vh] overflow-auto">
        <table className="min-w-full border-separate border-spacing-0 text-sm">
          <thead>
            <tr>
              <th
                rowSpan={2}
                className="sticky left-0 top-0 z-30 border-b border-r bg-card px-3 pb-2 text-left align-bottom font-medium text-muted-foreground"
              >
                <div className={FIRST_COLUMN_WIDTH}>{firstColumn}</div>
              </th>
              {dates.map((date) => (
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
              {dates.map((date) => (
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
                <td className="sticky left-0 z-10 border-b border-r bg-card px-3 py-2 align-top">
                  <div className={cn("flex items-start gap-2", FIRST_COLUMN_WIDTH)}>
                    {showIcons && <ReferrerSourceIcon source={row.key} />}
                    <div className="min-w-0">
                      <div className="[overflow-wrap:anywhere]">{wrapPath(displayLabel(row.key))}</div>
                      {row.title && row.title!==displayLabel(row.key) && <div className="text-xs text-muted-foreground">{row.title}</div>}
                    </div>
                  </div>
                </td>
                {[...row.cells].reverse().map((cell) => (
                  <Fragment key={cell.date}>
                    <ValueCell cell={cell} metric="VISITORS" className="border-l" />
                    <ValueCell cell={cell} metric="VIEWS" />
                  </Fragment>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>}
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
