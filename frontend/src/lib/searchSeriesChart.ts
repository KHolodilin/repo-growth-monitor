import type { GrowthEvent, SearchSeries } from "./api";
import { eventUtcDate } from "./growthEvents";
import { datesFromHistory, rankHistoryOption } from "./rankChart";
import { eventMarkOverlays } from "./trafficChart";
import { formatChartAxisDate, formatNumber } from "./utils";

export type SearchChartMetric = "place" | "stars" | "forks";
export type SearchChartAxis = "calendar" | "timeline";

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
    tracked: true,
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

type ChartLine = { name: string; tracked?: boolean; points: SearchChartPoint[] };

function hasStoredSnapshot(point: SearchChartPoint | undefined) {
  return point != null && (point.position != null || point.stars != null || point.forks != null);
}

/** Day 1 is each repository's first stored snapshot. Later misses stay empty points. */
export function timelineFrame(dates: string[], lines: ChartLine[], missedDates: string[]) {
  const missed = new Set(missedDates);
  const spans = lines.map((line) => {
    const byDate = new Map(line.points.map((point) => [point.date, point]));
    const start = dates.findIndex((date) => {
      const point = byDate.get(date);
      return line.tracked ? point != null : hasStoredSnapshot(point);
    });
    return { line, byDate, span: start < 0 ? [] : dates.slice(start) };
  });
  const length = Math.max(0, ...spans.map((item) => item.span.length));
  const axis = Array.from({ length }, (_, index) => String(index + 1));
  const longestSpan = spans.reduce(
    (best, item) => (item.span.length > best.length ? item.span : best),
    [] as string[],
  );
  return {
    dates: axis,
    dateToDay: new Map(longestSpan.map((date, index) => [date, String(index + 1)])),
    lines: spans.map(({ line, byDate, span }) => ({
      name: line.name,
      points: axis.flatMap((label, index) => {
        if (index >= span.length || missed.has(span[index])) {
          return [];
        }
        const point = byDate.get(span[index]);
        return [{
          date: label,
          position: point?.position ?? null,
          stars: point?.stars ?? null,
          forks: point?.forks ?? null,
        }];
      }),
      missedDays: axis.filter((label, index) => index < span.length && missed.has(span[index])),
    })),
  };
}

export function searchMetricOption({
  metric,
  axis = "calendar",
  dates,
  missedDates,
  limit,
  lines,
  events = [],
}: {
  metric: SearchChartMetric;
  axis?: SearchChartAxis;
  dates: string[];
  missedDates: string[];
  limit: number;
  lines: ChartLine[];
  events?: GrowthEvent[];
}) {
  const frame = axis === "timeline" ? timelineFrame(dates, lines, missedDates) : null;
  const axisDates = frame?.dates ?? dates;
  const formatAxis = axis === "timeline" ? (value: string) => `Day ${value}` : formatChartAxisDate;
  const markerAt = frame ? (event: GrowthEvent) => frame.dateToDay.get(eventUtcDate(event)) ?? "" : undefined;
  if (metric === "place") {
    return rankHistoryOption({
      dates: axisDates,
      formatAxis,
      legend: lines.length > 0,
      events,
      markerAt,
      series: (frame?.lines ?? lines).map((line) => ({
        name: line.name,
        limit,
        missedDates: "missedDays" in line ? line.missedDays : missedDates,
        points: line.points.map((point) => ({ date: point.date, position: point.position })),
      })),
    });
  }
  const field = metric === "stars" ? "stars" : "forks";
  const plotted = frame?.lines ?? lines;
  return countHistoryOption({
    dates: axisDates,
    formatAxis,
    events,
    markerAt,
    series: plotted.map((line) => ({
      name: line.name,
      data: axisDates.map((date) => line.points.find((point) => point.date === date)?.[field] ?? null),
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
  axis = "calendar",
  events = [],
}: {
  history: { points: { date: string; position: number | null }[]; missedDates: string[]; query: { resultLimit: number } } | null;
  repo: { githubId: number; fullName: string } | null;
  rows: ChartRow[] | null;
  series: SearchSeries["series"];
  selected: number[];
  metric: SearchChartMetric;
  axis?: SearchChartAxis;
  events?: GrowthEvent[];
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
      axis,
      dates: datesFromHistory([history.points], history.missedDates),
      missedDates: history.missedDates,
      limit: history.query.resultLimit,
      lines,
      events,
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

export function storedCountOption({
  dates,
  series,
}: {
  dates: string[];
  series: { name: string; data: (number | null)[] }[];
}) {
  return countHistoryOption({ dates, series });
}

function countHistoryOption({
  dates,
  series,
  formatAxis = formatChartAxisDate,
  events = [],
  markerAt,
}: {
  dates: string[];
  series: { name: string; data: (number | null)[] }[];
  formatAxis?: (value: string) => string;
  events?: GrowthEvent[];
  markerAt?: (event: GrowthEvent) => string;
}) {
  const overlays = events.length > 0 ? eventMarkOverlays(dates, events, 1, undefined, markerAt) : null;
  const eventSeries = overlays && overlays.groups.length > 0
    ? [{
        name: "Events",
        type: "line" as const,
        data: dates.map(() => null),
        showSymbol: false,
        lineStyle: { opacity: 0, width: 0 },
        tooltip: { show: false },
        markLine: overlays.markLine,
      }]
    : [];
  return {
    tooltip: {
      trigger: "axis" as const,
      formatter: (params: { axisValue: string; seriesName: string; data: number | null }[]) => {
        if (!Array.isArray(params) || params.length === 0) {
          return "";
        }
        const date = formatAxis(params[0].axisValue);
        const rows = params
          .filter((item) => item.seriesName !== "Events")
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
        formatter: (value: string) => formatAxis(String(value)),
      },
    },
    yAxis: { type: "value" as const },
    series: [
      ...series.map((item) => ({
        name: item.name,
        type: "line" as const,
        showSymbol: true,
        symbolSize: 8,
        connectNulls: false,
        data: item.data,
      })),
      ...eventSeries,
    ],
  };
}
