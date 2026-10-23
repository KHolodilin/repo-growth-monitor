import type { TopicHistory } from "../lib/api";
import { topicBandKpi, type TopicRankSample } from "../lib/topicVisibilityKpi";
import { Card } from "./ui";
import { TrafficKpiCard } from "./TrafficKpiCard";
import type { Period } from "./PeriodSelector";

const BANDS = [
  { band: 3, label: "Top 3" },
  { band: 10, label: "Top 10" },
  { band: 20, label: "Top 20" },
] as const;

export function TopicVisibilityKpis({ topics, period }: { topics: TopicHistory[]; period: Period }) {
  const today = new Date().toISOString().slice(0, 10);
  const samples: TopicRankSample[] = topics.map((item) => ({
    slug: item.watch.topic,
    currentRank: item.currentRank,
    points: item.points,
  }));
  return (
    <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
      <Card className="flex h-full flex-col p-3">
        <div className="text-sm text-muted-foreground">Topics</div>
        <div className="mt-1 text-2xl font-semibold tabular-nums">{topics.length}</div>
        <div className="mt-1 text-xs text-muted-foreground">Tracked topics</div>
      </Card>
      {BANDS.map((item) => {
        const kpi = topicBandKpi(samples, item.band, period, today);
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
            missingHistory={kpi.value == null}
          />
        );
      })}
    </div>
  );
}
