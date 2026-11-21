import { useEffect, useMemo, useState } from "react";
import { api, type RepositoryStatsHistory } from "../lib/api";
import { repoStatsChartId, STATS_SERIES } from "../lib/chartLegend";
import { statsChartOption } from "../lib/statsChart";
import { type Period } from "./PeriodSelector";
import { ChartCard } from "./ChartDownloadMenu";
import { PersistentECharts } from "./PersistentECharts";
import { TrafficKpiCard } from "./TrafficKpiCard";
import { Card } from "./ui";

const STATS_CARDS = [
  { key: "stars", label: "Stars" },
  { key: "forks", label: "Forks" },
  { key: "watchers", label: "Watchers" },
  { key: "contributors", label: "Contributors" },
] as const;

export function RepositoryStatsPanel({
  repositoryId,
  period,
  selectedMobileSeries, onMobileSeriesChange,
}: {
  repositoryId: number;
  period: Period;
  selectedMobileSeries?: string;
  onMobileSeriesChange?: (name: string) => void;
}) {
  const [history, setHistory] = useState<RepositoryStatsHistory | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setError(null);
    api<RepositoryStatsHistory>(`/api/v1/admin/repositories/${repositoryId}/stats-history?period=${period}`)
      .then((data) => {
        if (!cancelled) {
          setHistory(data);
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

  const option = useMemo(() => statsChartOption(history?.points ?? []), [history]);

  if (error) {
    return <p className="text-red-600">{error}</p>;
  }
  if (!history) {
    return (
      <div className="space-y-4">
        <Card>
          <div className="h-64 animate-pulse rounded-md bg-muted" />
        </Card>
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        {STATS_CARDS.map((card) => {
          const total = history.totals[card.key];
          return (
            <TrafficKpiCard
              key={card.key}
              label={card.label}
              value={total.value}
              growthPercent={total.growthPercent}
              absoluteChange={total.absoluteChange}
              period={period}
              series={history.points.map((point) => point[card.key])}

            />
          );
        })}
      </div>
      <ChartCard
        title="Stats"
        repositoryId={repositoryId}
        period={period}
        context={`repository:${repositoryId}:stats`}
        mobileSeries={STATS_SERIES}
        selectedMobileSeries={selectedMobileSeries}
        onMobileSeriesChange={onMobileSeriesChange}
      >
        {history.points.length === 0 ? (
          <div className="rounded-lg border border-dashed px-4 py-10 text-center text-sm text-muted-foreground">
            <div className="font-medium text-foreground">No stats for this period.</div>
            <div className="mt-1">Collect the repository to store the first daily snapshot.</div>
          </div>
        ) : (
          <PersistentECharts
            accessibleTitle="Repository Stats"
            chartId={repoStatsChartId(repositoryId)}
            series={STATS_SERIES}
            option={option}
            style={{ height: 360, width: "100%" }}
          />
        )}
      </ChartCard>
    </div>
  );
}
