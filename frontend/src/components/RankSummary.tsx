import { useIsMobileChart } from "../lib/useMediaQuery";
import { formatDelta, formatNumber, formatRank } from "../lib/utils";
import { Card } from "./ui";
export function RankSummary({ current, best, limit, change7d, change30d, total }: { current: number | null; best: number | null; limit: number; change7d: number | null; change30d: number | null; total: number | null | undefined }) {
  const mobile = useIsMobileChart();
  const values = { "Current Rank": formatRank(current, limit), "7d Change": formatDelta(change7d), "30d Change": formatDelta(change30d), "Best Rank": formatRank(best, limit), "Total Results": formatNumber(total) };
  if (!mobile) return <div className="grid grid-cols-2 gap-4 lg:grid-cols-5">{Object.entries(values).map(([label, value]) => <Card key={label}><div className="text-sm text-muted-foreground">{label}</div><div className="mt-2 text-2xl font-semibold">{value}</div></Card>)}</div>;
  return <Card className="p-4" aria-label="Rank summary">
    <dl className="grid grid-cols-2 gap-x-4 gap-y-3">
      {(["Current Rank", "Best Rank", "7d Change", "30d Change"] as const).map(label => <div key={label} className="min-w-0"><dt className="text-xs text-muted-foreground">{label}</dt><dd className={label.endsWith("Rank") ? "mt-1 text-xl font-semibold" : "mt-1 text-sm font-medium"}>{values[label]}{(label === "7d Change" ? change7d : label === "30d Change" ? change30d : undefined) === 0 && <span className="ml-1 text-xs font-normal text-muted-foreground">No change</span>}</dd></div>)}
      <div className="col-span-2 flex flex-wrap justify-between gap-2 border-t pt-3"><dt className="text-sm text-muted-foreground">Total Results</dt><dd className="break-all text-sm font-semibold">{values["Total Results"]}</dd></div>
    </dl>
  </Card>;
}
