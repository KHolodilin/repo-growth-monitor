import type { ReactNode } from "react";
import { Link } from "react-router-dom";
import type { QueryRankChange } from "../lib/api";
import { QUERY_SORT_KEYS, type QuerySortKey } from "../lib/querySort";
import { seriesStyle } from "../lib/seriesStyle";
import { formatDelta, formatNumber, formatQueryRankChange, formatRank, formatSyncTime, growthClass } from "../lib/utils";
import { SeriesSwatch } from "./SeriesSwatch";

type WatchRow = {
  id: number; name: string; href: string; seriesKey: string; checked: boolean; onToggle: () => void;
  rank: number | null; limit: number; change: QueryRankChange; change7d: number | null; change30d: number | null;
  best: number | null; total?: number | null; updated?: string | null; actions?: ReactNode;
};
export function WatchListCards({ rows, nameLabel, sortKey, sortDir, toggle, allChecked, onToggleAll }: {
  rows: WatchRow[]; nameLabel: "Topic" | "Search Query"; sortKey: QuerySortKey | null; sortDir: "asc" | "desc";
  toggle: (key: QuerySortKey) => void; allChecked: boolean; onToggleAll: () => void;
}) {
  const labels = { name: nameLabel, rank: "Rank", change: "Change", change7d: "7d", change30d: "30d", best: "Best", results: "Results", updated: "Updated" };
  return <div className="min-w-0 space-y-3" aria-label={`${nameLabel} watch-list`}>
    <div className="flex items-center gap-2 text-sm">
      <label className="flex min-w-0 flex-1 items-center gap-2">Sort by
        <select className="min-h-10 min-w-0 flex-1 rounded border bg-card px-2" value={sortKey ?? ""} onChange={event => toggle(event.target.value as QuerySortKey)}>
          <option value="" disabled>Default order</option>
          {QUERY_SORT_KEYS.map(key => <option key={key} value={key}>{labels[key]}</option>)}
        </select>
      </label>
      <button type="button" aria-label="Reverse sort direction" disabled={!sortKey} className="min-h-10 rounded border px-3" onClick={() => toggle(sortKey!)}>{sortDir === "asc" ? "↑" : "↓"}</button>
    </div>
    <label className="flex min-h-11 items-center gap-2 text-sm"><input type="checkbox" aria-label="Chart lines" checked={allChecked} ref={node => { if (node) node.indeterminate = !allChecked && rows.some(row => row.checked); }} onChange={onToggleAll} />Select all chart lines</label>
    {rows.map(row => {
      const change = formatQueryRankChange(row.change);
      const metrics = [{ label: "Rank", value: formatRank(row.rank, row.limit) }, { label: "Change", value: <span className={growthClass(change.direction)}>{change.label}</span> }, { label: "7d", value: formatDelta(row.change7d) }, { label: "30d", value: formatDelta(row.change30d) }, { label: "Best", value: formatRank(row.best, row.limit) }, { label: "Results", value: formatNumber(row.total) }, { label: "Updated", value: formatSyncTime(row.updated) ?? "—" }];
      return <article key={row.id} className="min-w-0 rounded-lg border bg-card p-3">
        <div className="flex min-w-0 items-start gap-2 font-medium">{row.checked && <SeriesSwatch style={seriesStyle(row.seriesKey)} />}<Link className="min-w-0 text-primary hover:underline [overflow-wrap:anywhere]" to={row.href}>{row.name}</Link></div>
        <dl className="mt-3 grid grid-cols-2 gap-x-3 gap-y-2 text-sm">{metrics.map(metric => <div key={metric.label} className={metric.label === "Updated" ? "col-span-2 min-w-0" : "min-w-0"}><dt className="text-xs text-muted-foreground">{metric.label}</dt><dd className="[overflow-wrap:anywhere]">{metric.value}</dd></div>)}</dl>
        <label className="mt-2 flex min-h-11 items-center gap-2 text-sm"><input type="checkbox" className="h-4 w-4" aria-label={`Chart line for ${row.name}`} checked={row.checked} onChange={row.onToggle} />Show on chart</label>
        {row.actions && <div className="mt-2 flex flex-wrap gap-2">{row.actions}</div>}
      </article>;
    })}
  </div>;
}
