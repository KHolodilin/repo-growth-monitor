import type { SearchHistory } from "../lib/api";
import { bandKpi, searchQueryUsage, type RankSample } from "../lib/searchVisibilityKpi";
import { Card } from "./ui";
import { TrafficKpiCard } from "./TrafficKpiCard";
import type { Period } from "./PeriodSelector";

const BANDS = [
  { band: 3, label: "Top 3" },
  { band: 10, label: "Top 10" },
  { band: 20, label: "Top 20" },
] as const;

export function SearchVisibilityKpis({ queries, period }: { queries: SearchHistory[]; period: Period }) {
  const today = new Date().toISOString().slice(0, 10);
  const samples: RankSample[] = queries.map((item) => ({
    id: item.query.id,
    enabled: item.query.enabled,
    currentRank: item.currentRank,
    points: item.points,
  }));
  const usage = searchQueryUsage(samples.filter((item) => item.enabled).length);
  return (
    <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
      <Card className="flex h-full flex-col p-3">
        <div className="text-sm text-muted-foreground">Search Queries</div>
        <div className="mt-1 text-2xl font-semibold tabular-nums">
          {usage.used} / {usage.limit}
        </div>
        <div className="mt-2 h-1 overflow-hidden rounded-full bg-muted" aria-hidden="true">
          <div className="h-full rounded-full bg-primary" style={{ width: `${usage.progress}%` }} />
        </div>
        <div className="mt-1 text-xs text-muted-foreground">{usage.available} available</div>
      </Card>
      {BANDS.map((item) => {
        const kpi = bandKpi(samples, item.band, period, today);
        const missingHistory = kpi.value == null || (period !== "all" && kpi.absoluteChange == null);
        return (
          <TrafficKpiCard
            key={item.label}
            label={item.label}
            value={kpi.value}
            growthPercent={kpi.growthPercent}
            absoluteChange={kpi.absoluteChange}
            period={period}
            series={kpi.series}
            preserveGaps
            missingHistory={missingHistory}
          />
        );
      })}
    </div>
  );
}
