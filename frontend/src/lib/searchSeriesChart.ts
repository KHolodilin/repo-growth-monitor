import type { Period } from "../components/PeriodSelector";
import { periodDates } from "./searchVisibilityKpi";
import { lineStyle, prepareSeriesStyles, seriesStyle } from "./seriesStyle";
import type { GrowthEvent, SearchSeries } from "./api";
import { eventUtcDate } from "./growthEvents";
import { datesFromHistory, rankHistoryOption } from "./rankChart";
import { eventMarkOverlays } from "./trafficChart";
import { formatChartAxisDate, formatNumber } from "./utils";

export type SearchChartMetric = "place" | "stars" | "forks";
export const SEARCH_METRICS = {
  place: { label: "Rank", title: "Rank History" },
  stars: { label: "Stars", title: "Stars History" },
  forks: { label: "Forks", title: "Forks History" },
} as const;

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
    key?: string;
    rank: { date: string; position: number | null }[];
    stored: { date: string; stars: number; forks: number }[];
  };
  neighbors: { name: string; key?: string; stored: { date: string; position: number; stars: number; forks: number }[] }[];
}): { name: string; key?: string; points: SearchChartPoint[] }[] {
  const ourRank = new Map(ours.rank.map((point) => [point.date, point.position]));
  const ourStored = new Map(ours.stored.map((point) => [point.date, point]));
  const oursLine = {
    name: ours.name,
    key: ours.key,
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
      key: neighbor.key,
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

type ChartLine = { name: string; key?: string; tracked?: boolean; points: SearchChartPoint[] };

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
    eventAxisDates: longestSpan,
    seriesDates: Object.fromEntries(spans.map(item => [item.line.name, item.span])),
    dateToDay: new Map(longestSpan.map((date, index) => [date, String(index + 1)])),
    lines: spans.map(({ line, byDate, span }) => ({
      name: line.name,
      key: line.key,
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
  period = "all",
  today = new Date().toISOString().slice(0, 10),
}: {
  metric: SearchChartMetric;
  axis?: SearchChartAxis;
  dates: string[];
  missedDates: string[];
  limit: number;
  lines: ChartLine[];
  events?: GrowthEvent[];
  period?: Period;
  today?: string;
}) {
  const window = periodDates(period, today);
  if (window) {
    dates = window;
    const onAxis = new Set(window);
    events = events.filter(event => onAxis.has(eventUtcDate(event)));
  }
  const frame = axis === "timeline" ? timelineFrame(dates, lines, missedDates) : null;
  const axisDates = frame?.dates ?? dates;
  const formatAxis = axis === "timeline" ? (value: string) => `Day ${value}` : formatChartAxisDate;
  const markerAt = frame ? (event: GrowthEvent) => frame.dateToDay.get(eventUtcDate(event)) ?? "" : undefined;
  const tooltipDate = frame ? (name: string, day: string) => frame.seriesDates[name]?.[Number(day) - 1] : undefined;
  if (metric === "place") {
    const option = rankHistoryOption({
      dates: axisDates,
      formatAxis,
      legend: lines.length > 0,
      events,
      markerAt,
      tooltipDate,
      series: (frame?.lines ?? lines).map((line) => ({
        name: line.name,
        key: line.key,
        limit,
        missedDates: "missedDays" in line ? line.missedDays : missedDates,
        points: line.points.map((point) => ({ date: point.date, position: point.position })),
      })),
    });
    return { ...labelMetricOption(option, metric, frame?.eventAxisDates ?? option.eventAxisDates), collectionDates: lines.flatMap(line => line.points.filter(hasStoredSnapshot).map(point => point.date)), missedCollectionDates: missedDates };
  }
  const field = metric === "stars" ? "stars" : "forks";
  const plotted = frame?.lines ?? lines;
  return { ...labelMetricOption(countHistoryOption({
    dates: axisDates,
    formatAxis,
    events,
    markerAt,
    tooltipDate,
    series: plotted.map((line) => ({
      name: line.name,
      key: line.key,
      data: axisDates.map((date) => line.points.find((point) => point.date === date)?.[field] ?? null),
    })),
  }), metric, frame?.eventAxisDates ?? dates), collectionDates: lines.flatMap(line => line.points.filter(hasStoredSnapshot).map(point => point.date)), missedCollectionDates: missedDates };
}

/** Query-only labels leave shared topic/rank consumers unchanged. */
function labelMetricOption<T extends ReturnType<typeof rankHistoryOption> | ReturnType<typeof countHistoryOption>>(option: T, metric: SearchChartMetric, eventAxisDates: string[]) {
  const { label } = SEARCH_METRICS[metric];
  const formatter = option.tooltip.formatter;
  return {
    ...option,
    collectionSeries: [],
    eventAxisDates,
    exportLabels: Object.fromEntries(option.series.filter(item => item.name !== "Events").map(item => [item.name, `${item.name} (${label})`])),
    yAxis: { ...option.yAxis, name: label, nameLocation: metric === "place" ? "start" : "end", nameGap: 8, nameTextStyle: { fontSize: 11 } },
    tooltip: { ...option.tooltip, formatter: (params: Parameters<typeof formatter>[0]) => {
      const html = formatter(params as never);
      return html ? html.replace("<div>", `<div>${label} · `) : html;
    } },
  };
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
  period = "all",
  today = new Date().toISOString().slice(0, 10),
}: {
  history: { points: { date: string; position: number | null }[]; missedDates: string[]; query: { resultLimit: number; enabled?: boolean } } | null;
  repo: { githubId: number; fullName: string } | null;
  rows: ChartRow[] | null;
  series: SearchSeries["series"];
  selected: number[];
  metric: SearchChartMetric;
  axis?: SearchChartAxis;
  events?: GrowthEvent[];
  period?: Period;
  today?: string;
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
    return [{ key: `repository:${id}`, name: row.result.fullName, stored: storedById.get(id)?.points ?? [] }];
  });
  const lines = queryChartLines({
    snapshotDates: history.points.map((point) => point.date),
    ours: {
      name: repo.fullName,
      key: `repository:${repo.githubId}`,
      rank: history.points,
      stored: storedById.get(repo.githubId)?.points ?? [],
    },
    neighbors,
  });
  return {
    option: { ...searchMetricOption({
      metric,
      axis,
      dates: datesFromHistory([history.points], history.missedDates),
      missedDates: history.missedDates,
      limit: history.query.resultLimit,
      lines,
      events,
      period,
      today,
    }), collectionDates: history.query.enabled === false ? [] : history.points.map(point => point.date), missedCollectionDates: history.query.enabled === false ? [] : history.missedDates },
    series: [
      { key: `repository:${repo.githubId}`, name: repo.fullName },
      ...selected.flatMap((id) => {
        const row = rows?.find((item) => item.result.githubRepositoryId === id);
        if (!row || id === repo.githubId) {
          return [];
        }
        return [{ key: `repository:${id}`, name: row.result.fullName }];
      }),
    ],
  };
}

export function storedCountOption({
  dates,
  series,
  period = "all",
  today = new Date().toISOString().slice(0, 10),
}: {
  dates: string[];
  series: { name: string; key?: string; data: (number | null)[] }[];
  period?: Period;
  today?: string;
}) {
  const window = periodDates(period, today);
  if (!window) return { ...countHistoryOption({ dates, series }), collectionDates: dates };
  const indexes = new Map(dates.map((date, index) => [date, index]));
  return { ...countHistoryOption({ dates: window, series: series.map(item => ({ ...item, data: window.map(date => {
    const index = indexes.get(date);
    return index == null ? null : (item.data[index] ?? null);
  }) })) }), collectionDates: dates };
}

function countHistoryOption({
  dates,
  series,
  formatAxis = formatChartAxisDate,
  events = [],
  markerAt,
  tooltipDate,
}: {
  dates: string[];
  series: { name: string; key?: string; data: (number | null)[] }[];
  formatAxis?: (value: string) => string;
  events?: GrowthEvent[];
  markerAt?: (event: GrowthEvent) => string;
  tooltipDate?: (seriesName: string, axisValue: string) => string | undefined;
}) {
  prepareSeriesStyles(series.map(item => item.key ?? item.name));
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
            const identity = series.find(line => line.name === item.seriesName);
            const observedOn = tooltipDate?.(item.seriesName, item.axisValue);
            return `<div><span style="color:${seriesStyle(identity?.key ?? item.seriesName).color}">●</span> ${item.seriesName}${observedOn ? ` (${observedOn})` : ""}: ${value}</div>`;
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
        ...lineStyle(item.key ?? item.name),
        type: "line" as const,
        showSymbol: true,
        symbolSize: 8,
        connectNulls: true,
        data: item.data,
      })),
      ...eventSeries,
    ],
  };
}
