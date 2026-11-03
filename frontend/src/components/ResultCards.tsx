import type { ReactNode } from "react";

export function ResultCards({ rows, fields, sortKey, sortDir, toggle }: {
  rows: { id: number; position: number; name: ReactNode; tracked: boolean; selection?: ReactNode; metrics: { label: string; value: ReactNode }[] }[];
  fields: readonly string[]; sortKey: string | null; sortDir: "asc" | "desc"; toggle: (key: string) => void;
}) {
  return <div className="min-w-0 space-y-3">
    <div className="flex items-center gap-2 text-sm">
      <label className="flex min-w-0 flex-1 items-center gap-2">Sort by
        <select className="min-h-10 min-w-0 flex-1 rounded border bg-card px-2" value={sortKey ?? ""} onChange={event => toggle(event.target.value)}>
          <option value="" disabled>Snapshot order</option>
          {fields.map(key => <option key={key} value={key}>{({ fullName: "Repository", position: "Position", delta: "Change" } as Record<string, string>)[key] ?? key[0].toUpperCase() + key.slice(1)}</option>)}
        </select>
      </label>
      <button type="button" disabled={!sortKey} aria-label="Reverse sort direction" className="min-h-10 rounded border px-3" onClick={() => toggle(sortKey!)}>{sortDir === "asc" ? "↑" : "↓"}</button>
    </div>
    {rows.length === 0 && <p className="text-sm text-muted-foreground">No results in this snapshot.</p>}
    {rows.map(row => <article key={row.id} className={`min-w-0 rounded-lg border p-3 ${row.tracked ? "bg-blue-50" : "bg-card"}`}>
      <div className="flex items-start gap-2 font-medium"><span className="shrink-0">#{row.position}</span><div className="min-w-0 [overflow-wrap:anywhere]">{row.name}</div></div>
      {row.tracked && <p className="mt-1 text-xs text-muted-foreground">Tracked repository</p>}
      <dl className="mt-3 grid grid-cols-2 gap-x-3 gap-y-2 text-sm">{row.metrics.map(metric => <div key={metric.label} className="min-w-0"><dt className="text-xs text-muted-foreground">{metric.label}</dt><dd className="[overflow-wrap:anywhere]">{metric.value}</dd></div>)}</dl>
      {row.selection}
    </article>)}
  </div>;
}
export function ChartSelection({ name, checked, onChange }: { name: string; checked: boolean; onChange: () => void }) {
  return <label className="mt-2 flex min-h-11 cursor-pointer items-center gap-2 text-sm"><input type="checkbox" className="h-4 w-4" checked={checked} aria-label={`Show ${name} on the chart`} onChange={onChange} />Show on chart</label>;
}
