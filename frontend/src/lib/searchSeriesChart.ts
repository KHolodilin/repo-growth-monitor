import type { SearchSeries } from "./api";
import { datesFromHistory, rankHistoryOption } from "./rankChart";
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

type ChartRow = { result: { githubRepositoryId: number; fullName: string } };

/** Skip our own id and repos that are no longer in the current result page. */
export function queryDetailsChart({
  history,
  repo,
  rows,
  series,
  selected,
  metric,
}: {
  history: { points: { date: string; position: number | null }[]; missedDates: string[]; query: { resultLimit: number } } | null;
  repo: { githubId: number; fullName: string } | null;
  rows: ChartRow[] | null;
  series: SearchSeries["series"];
  selected: number[];
  metric: SearchChartMetric;
}) {
  if (!history || !repo) {
    return null;
  }
  const storedById = new Map(series.map((item) => [item.githubRepositoryId, item]));
  const neighbors = selected.flatMap((id) => {
    if (id === repo.githubId) {
      return [];
    }
    const row = rows?.find((item) => item.result.githubRepositoryId === id);
    if (!row) {
      return [];
    }
    return [{ name: row.result.fullName, stored: storedById.get(id)?.points ?? [] }];
  });
  const lines = queryChartLines({
    snapshotDates: history.points.map((point) => point.date),
    ours: {
      name: repo.fullName,
      rank: history.points,
      stored: storedById.get(repo.githubId)?.points ?? [],
    },
    neighbors,
  });
  return {
    option: searchMetricOption({
      metric,
      dates: datesFromHistory([history.points], history.missedDates),
      missedDates: history.missedDates,
      limit: history.query.resultLimit,
      lines,
    }),
    series: [
      { key: String(repo.githubId), name: repo.fullName },
      ...selected.flatMap((id) => {
        const row = rows?.find((item) => item.result.githubRepositoryId === id);
        if (!row || id === repo.githubId) {
          return [];
        }
        return [{ key: String(id), name: row.result.fullName }];
      }),
    ],
  };
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
