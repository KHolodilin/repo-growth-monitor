import { prepareSeriesStyles, seriesStyle } from "../lib/seriesStyle";
import { SeriesSwatch } from "./SeriesSwatch";
/** Competitor comparison is simultaneous; these labels do not hide individual lines. */
export function ComparisonSeries({ series }: { series: { key: string; name: string }[] }) {
  prepareSeriesStyles(series.map(item => item.key));
  return <ul aria-label="Compared repositories" className="mb-3 flex flex-wrap gap-x-3 gap-y-1 text-xs text-muted-foreground md:hidden">
    {series.map(item => <li key={item.key} className="min-w-0 [overflow-wrap:anywhere]"><SeriesSwatch style={seriesStyle(item.key)} /> {item.name}</li>)}
  </ul>;
}
