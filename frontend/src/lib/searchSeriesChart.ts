import type { SearchSeries } from "./api";
import { rankHistoryOption } from "./rankChart";
import { formatChartAxisDate, formatNumber } from "./utils";

export type SearchChartMetric = "place" | "stars" | "forks";

export type SearchChartPoint = {
  date: string;
  position: number | null;
  stars: number | null;
  forks: number | null;
};

export function searchSeriesFromPayload(payload: unknown): SearchSeries["series"] {
  if (!payload || typeof payload !== "object" || !("series" in payload)) {
    return [];
  }
  const series = (payload as { series?: unknown }).series;
  return Array.isArray(series) ? series : [];
}

/** Our rank comes from the tracked history. Stars and forks come from the stored result rows. */
export function queryChartLines({
  snapshotDates,
  ours,
  neighbors,
}: {
  snapshotDates: string[];
  ours: {
    name: string;
    rank: { date: string; position: number | null }[];
    stored: { date: string; stars: number; forks: number }[];
  };
  neighbors: { name: string; stored: { date: string; position: number; stars: number; forks: number }[] }[];
}): { name: string; points: SearchChartPoint[] }[] {
  const ourRank = new Map(ours.rank.map((point) => [point.date, point.position]));
  const ourStored = new Map(ours.stored.map((point) => [point.date, point]));
  const oursLine = {
    name: ours.name,
    points: snapshotDates.map((date) => {
      const stored = ourStored.get(date);
      return {
        date,
        position: ourRank.get(date) ?? null,
        stars: stored?.stars ?? null,
        forks: stored?.forks ?? null,
      };
    }),
  };
  const neighborLines = neighbors.map((neighbor) => {
    const stored = new Map(neighbor.stored.map((point) => [point.date, point]));
    return {
      name: neighbor.name,
      points: snapshotDates.map((date) => {
        const point = stored.get(date);
        if (!point) {
          return { date, position: null, stars: null, forks: null };
        }
        return { date, position: point.position, stars: point.stars, forks: point.forks };
      }),
    };
  });
  return [oursLine, ...neighborLines];
}

export function searchMetricOption({
  metric,
  dates,
  missedDates,
  limit,
  lines,
}: {
  metric: SearchChartMetric;
  dates: string[];
  missedDates: string[];
  limit: number;
  lines: { name: string; points: SearchChartPoint[] }[];
}) {
  if (metric === "place") {
    return rankHistoryOption({
      dates,
      legend: lines.length > 0,
      series: lines.map((line) => ({
        name: line.name,
        limit,
        missedDates,
        points: line.points.map((point) => ({ date: point.date, position: point.position })),
      })),
    });
  }
  const field = metric === "stars" ? "stars" : "forks";
  return countHistoryOption({
    dates,
    series: lines.map((line) => ({
      name: line.name,
      data: dates.map((date) => line.points.find((point) => point.date === date)?.[field] ?? null),
    })),
  });
}

function countHistoryOption({
  dates,
  series,
}: {
  dates: string[];
  series: { name: string; data: (number | null)[] }[];
}) {
  return {
    tooltip: {
      trigger: "axis" as const,
      formatter: (params: { axisValue: string; seriesName: string; data: number | null }[]) => {
        if (!Array.isArray(params) || params.length === 0) {
          return "";
        }
        const date = formatChartAxisDate(params[0].axisValue);
        const rows = params
          .map((item) => {
            const value = item.data === null || item.data === undefined ? "—" : formatNumber(item.data);
            return `<div>${item.seriesName}: ${value}</div>`;
          })
          .join("");
        return `<div>${date}${rows}</div>`;
      },
    },
    legend: { top: 0, type: "scroll" as const, data: series.map((item) => item.name) },
    grid: { left: 48, right: 72, top: 48, bottom: 40, containLabel: false },
    xAxis: {
      type: "category" as const,
      data: dates,
      boundaryGap: dates.length < 2,
      axisLabel: {
        hideOverlap: true,
        showMinLabel: true,
        showMaxLabel: true,
        formatter: (value: string) => formatChartAxisDate(String(value)),
      },
    },
    yAxis: { type: "value" as const },
    series: series.map((item) => ({
      name: item.name,
      type: "line" as const,
      showSymbol: true,
      symbolSize: 8,
      connectNulls: false,
      data: item.data,
    })),
  };
}
