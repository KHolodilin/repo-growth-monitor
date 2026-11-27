import { useEffect, useMemo, useState } from "react";
import { api, type RepositoryStatsHistory } from "../lib/api";
import { repositoryStatsKpi, type StatsKpiResponse } from "../lib/kpiApi";
import { repoStatsChartId, STATS_SERIES } from "../lib/chartLegend";
import { statsChartOption } from "../lib/statsChart";
import { type Period } from "./PeriodSelector";
import { ChartCard } from "./ChartDownloadMenu";
import { PersistentECharts } from "./PersistentECharts";
import { TrafficKpiCard } from "./TrafficKpiCard";
import { Card } from "./ui";

const STATS_CARDS = [
  { key: "stars" as const, label: "Stars" },
  { key: "forks" as const, label: "Forks" },
  { key: "watchers" as const, label: "Watchers" },
  { key: "contributors" as const, label: "Contributors" },
];

export function RepositoryStatsPanel({
  repositoryId,
  period,
  selectedMobileSeries,
  onMobileSeriesChange,
}: {
  repositoryId: number;
  period: Period;
  selectedMobileSeries?: string;
  onMobileSeriesChange?: (name: string) => void;
}) {
  const loadKey = `${repositoryId}:${period}`;
  const [seenKey, setSeenKey] = useState(loadKey);
  const [history, setHistory] = useState<RepositoryStatsHistory | null>(null);
  const [kpi, setKpi] = useState<StatsKpiResponse | null>(null);
  const [error, setError] = useState<string | null>(null);

  if (loadKey !== seenKey) {
    setSeenKey(loadKey);
    setError(null);
    setHistory(null);
    setKpi(null);
  }

  useEffect(() => {
    let cancelled = false;
    Promise.all([
      repositoryStatsKpi(repositoryId, period),
      api<RepositoryStatsHistory>(
        `/api/v1/repositories/${repositoryId}/stats-history?period=${period}`,
      ),
    ])
      .then(([kpiData, historyData]) => {
        if (!cancelled) {
          setKpi(kpiData);
          setHistory(historyData);
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
  if (!history || !kpi) {
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
          const metric = kpi.metrics[card.key];
          const value = metric.value == null ? null : Number(metric.value);
          const absoluteChange =
            metric.change?.absolute == null ? null : Number(metric.change.absolute);
          const growthPercent =
            metric.change?.percent == null ? null : Number(metric.change.percent);
          return (
            <TrafficKpiCard
              key={card.key}
              label={card.label}
              value={value}
              growthPercent={growthPercent}
              absoluteChange={absoluteChange}
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
