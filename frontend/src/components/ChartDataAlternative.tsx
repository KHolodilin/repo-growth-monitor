import { useState } from "react";
import { chartTable } from "../lib/chartExport";
export function ChartDataAlternative({ option, title }: { option: object; title: string }) {
  const [open, setOpen] = useState(false);
  const table = chartTable(option);
  return <details className="mt-3 text-sm" onToggle={event => setOpen(event.currentTarget.open)}>
    <summary className="cursor-pointer rounded py-2 font-medium text-primary focus-visible:outline-2">View historical data</summary>
    <p className="my-2 text-xs text-muted-foreground">All recorded series. — means no observation; zero is a measured value.</p>
    {open && (table.dates.length === 0 ? <p>No observations for this period.</p> : <div className="max-h-80 overflow-auto"><table className="w-full text-sm"><caption className="sr-only">{title} historical values</caption><thead><tr><th scope="col" className="p-2 text-left">Date / Timeline day</th>{table.columns.map(column => <th scope="col" key={column.name} className="p-2 text-right">{column.name}</th>)}</tr></thead><tbody>{table.dates.map((date, index) => <tr key={date} className="border-t"><th scope="row" className="whitespace-nowrap p-2 text-left font-normal">{date}</th>{table.columns.map(column => <td key={column.name} className="p-2 text-right">{column.values[index] || "—"}</td>)}</tr>)}</tbody></table></div>)}
  </details>;
}
