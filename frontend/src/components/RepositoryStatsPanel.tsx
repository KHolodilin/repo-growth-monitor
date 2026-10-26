import { useEffect, useMemo, useState } from "react";
import { api, type RepositoryStatsHistory } from "../lib/api";
import { repoStatsChartId, STATS_SERIES } from "../lib/chartLegend";
import { statsChartOption } from "../lib/statsChart";
import { formatChartAxisDate, formatNumber } from "../lib/utils";
import { type Period } from "./PeriodSelector";
import { ChartCard } from "./ChartDownloadMenu";
import { PersistentECharts } from "./PersistentECharts";
import { TrafficKpiCard } from "./TrafficKpiCard";
import { Card } from "./ui";

const STATS_CARDS = [
  { key: "stars", label: "Stars", color: "#5470c6" },
  { key: "forks", label: "Forks", color: "#91cc75" },
  { key: "watchers", label: "Watchers", color: "#fac858" },
  { key: "contributors", label: "Contributors", color: "#ee6666" },
] as const;

export function RepositoryStatsPanel({
  repositoryId,
  period,
}: {
  repositoryId: number;
  period: Period;
}) {
  const [history, setHistory] = useState<RepositoryStatsHistory | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setError(null);
    api<RepositoryStatsHistory>(`/api/v1/repositories/${repositoryId}/stats-history?period=${period}`)
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
  const rows = useMemo(() => [...(history?.points ?? [])].reverse(), [history]);

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
              sparklineColor={card.color}
            />
          );
        })}
      </div>
      <ChartCard title="Stats" repositoryId={repositoryId} period={period} context={`repository:${repositoryId}:stats`}>
        {history.points.length === 0 ? (
          <div className="rounded-lg border border-dashed px-4 py-10 text-center text-sm text-muted-foreground">
            <div className="font-medium text-foreground">No stats for this period.</div>
            <div className="mt-1">Collect the repository to store the first daily snapshot.</div>
          </div>
        ) : (
          <PersistentECharts
            chartId={repoStatsChartId(repositoryId)}
            series={STATS_SERIES}
            option={option}
            style={{ height: 360, width: "100%" }}
          />
        )}
      </ChartCard>
      {history.points.length > 0 && (
        <Card>
          <h2 className="mb-3 font-medium">Daily totals</h2>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-left text-muted-foreground">
                  <th className="py-2 pr-4 font-medium">Date</th>
                  <th className="py-2 pl-2 text-right font-medium">Stars</th>
                  <th className="py-2 pl-2 text-right font-medium">Forks</th>
                  <th className="py-2 pl-2 text-right font-medium">Watchers</th>
                  <th className="py-2 pl-2 text-right font-medium">Contributors</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => (
                  <tr key={row.date} className="border-t">
                    <td className="whitespace-nowrap py-2 pr-4">{formatChartAxisDate(row.date)}</td>
                    <td className="whitespace-nowrap py-2 pl-2 text-right tabular-nums">{formatNumber(row.stars)}</td>
                    <td className="whitespace-nowrap py-2 pl-2 text-right tabular-nums">{formatNumber(row.forks)}</td>
                    <td className="whitespace-nowrap py-2 pl-2 text-right tabular-nums">{formatNumber(row.watchers)}</td>
                    <td className="whitespace-nowrap py-2 pl-2 text-right tabular-nums">{formatNumber(row.contributors)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>
      )}
    </div>
  );
}
