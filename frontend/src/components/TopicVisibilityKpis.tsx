import { useEffect, useState } from "react";
import { repositoryTopicsKpi, type TopicsKpiResponse } from "../lib/kpiApi";
import { Card } from "./ui";
import { TrafficKpiCard } from "./TrafficKpiCard";
import type { Period } from "./PeriodSelector";

const BANDS = [
  { key: "top3" as const, label: "Top 3" },
  { key: "top10" as const, label: "Top 10" },
  { key: "top20" as const, label: "Top 20" },
];

export function TopicVisibilityKpis({
  repositoryId,
  period,
  scope = "all",
  topicCount,
}: {
  repositoryId: number | string;
  period: Period;
  scope?: "all" | "language";
  topicCount: number;
}) {
  const loadKey = `${repositoryId}:${period}:${scope}`;
  const [seenKey, setSeenKey] = useState(loadKey);
  const [kpi, setKpi] = useState<TopicsKpiResponse | null>(null);
  const [error, setError] = useState<string | null>(null);

  if (loadKey !== seenKey) {
    setSeenKey(loadKey);
    setKpi(null);
    setError(null);
  }

  useEffect(() => {
    let cancelled = false;
    repositoryTopicsKpi(repositoryId, period, scope)
      .then((data) => {
        if (!cancelled) {
          setKpi(data);
        }
      })
      .catch((err: Error) => {
        if (!cancelled) {
          setError(err.message);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [repositoryId, period, scope]);

  if (error) {
    return <p className="text-red-600">{error}</p>;
  }
  if (!kpi?.metrics) {
    return (
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        {Array.from({ length: 4 }).map((_, index) => (
          <Card key={index} className="h-28 animate-pulse bg-muted" />
        ))}
      </div>
    );
  }

  return (
    <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
      <Card className="flex h-full flex-col p-3">
        <div className="text-sm text-muted-foreground">Topics</div>
        <div className="mt-1 text-2xl font-semibold tabular-nums">{topicCount}</div>
        <div className="mt-1 text-xs text-muted-foreground">Tracked topics</div>
      </Card>
      {BANDS.map((item) => {
        const band = kpi.metrics[item.key];
        const value = band.kpi.value == null ? null : Number(band.kpi.value);
        const absoluteChange =
          band.kpi.change?.absolute == null ? null : Number(band.kpi.change.absolute);
        const growthPercent =
          band.kpi.change?.percent == null ? null : Number(band.kpi.change.percent);
        return (
          <TrafficKpiCard
            key={item.label}
            label={item.label}
            value={value}
            growthPercent={growthPercent}
            absoluteChange={absoluteChange}
            period={period}
            series={band.series}
            preserveGaps
            missingHistory={value == null}
          />
        );
      })}
    </div>
  );
}
