import { useEffect, useState } from "react";
import { useMutationAccess } from "../lib/mutationAccess";
import { repositorySearchKpi, type SearchKpiResponse } from "../lib/kpiApi";
import { Card } from "./ui";
import { TrafficKpiCard } from "./TrafficKpiCard";
import type { Period } from "./PeriodSelector";

const BANDS = [
  { key: "top3" as const, label: "Top 3" },
  { key: "top10" as const, label: "Top 10" },
  { key: "top20" as const, label: "Top 20" },
];

export function SearchVisibilityKpis({
  repositoryId,
  period,
}: {
  repositoryId: number | string;
  period: Period;
}) {
  const { allowMutations } = useMutationAccess();
  const loadKey = `${repositoryId}:${period}`;
  const [seenKey, setSeenKey] = useState(loadKey);
  const [kpi, setKpi] = useState<SearchKpiResponse | null>(null);
  const [error, setError] = useState<string | null>(null);

  if (loadKey !== seenKey) {
    setSeenKey(loadKey);
    setKpi(null);
    setError(null);
  }

  useEffect(() => {
    let cancelled = false;
    repositorySearchKpi(repositoryId, period)
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
  }, [repositoryId, period]);

  if (error) {
    return <p className="text-red-600">{error}</p>;
  }
  if (!kpi?.usage || !kpi.metrics) {
    return (
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        {Array.from({ length: 4 }).map((_, index) => (
          <Card key={index} className="h-28 animate-pulse bg-muted" />
        ))}
      </div>
    );
  }

  const usage = kpi.usage;
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
        <div className="mt-1 text-xs text-muted-foreground">
          {allowMutations ? `${usage.available} available` : "Tracked search queries"}
        </div>
      </Card>
      {BANDS.map((item) => {
        const band = kpi.metrics[item.key];
        const value = band.kpi.value == null ? null : Number(band.kpi.value);
        const absoluteChange =
          band.kpi.change?.absolute == null ? null : Number(band.kpi.change.absolute);
        const growthPercent =
          band.kpi.change?.percent == null ? null : Number(band.kpi.change.percent);
        const missingHistory = value == null || (period !== "all" && absoluteChange == null);
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
            missingHistory={missingHistory}
          />
        );
      })}
    </div>
  );
}
