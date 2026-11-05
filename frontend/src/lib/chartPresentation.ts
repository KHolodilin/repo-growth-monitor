import { periodDates } from "./searchVisibilityKpi";
import type { Period } from "../components/PeriodSelector";
import type { GrowthEvent } from "./api";
import { eventMarkOverlays } from "./trafficChart";
import { formatChartAxisDate } from "./utils";

export type ChartAxis = "calendar" | "timeline";

const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;

type MarkItem = { xAxis?: string | number; yAxis?: number };
type Series = {
  [key: string]: unknown;
  type?: string;
  showSymbol?: boolean;
  showAllSymbol?: boolean;
  name?: string;
  data?: unknown;
  markLine?: { data?: MarkItem[] };
  markPoint?: { data?: MarkItem[]; [key: string]: unknown };
};
type ChartOption = {
  snapshotHistory?: boolean;
  collectionDates?: string[];
  collectionSeries?: { name: string; dates: string[] }[];
  missedCollectionDates?: string[];
  partialCollectionDates?: string[];
  collectionWarning?: { date: string; text: string };

  /** ISO dates aligned with rendered axis slots; independent of Day N labels. */
  eventAxisDates?: string[];
  yAxis?: { name?: string; inverse?: boolean } | unknown[];
  xAxis?: { data?: unknown; axisLabel?: Record<string, unknown> } | unknown[];
  series?: Series[];
  tooltip?: { formatter?: (params: unknown) => unknown };
  grid?: Record<string, unknown>;
};

/** Day 1 is each series' first stored value. The axis is as long as the longest span. */
export function timelineColumns(dates: string[], columns: (number | null | undefined)[][]) {
  const spans = columns.map((column) => {
    const start = column.findIndex((value) => value != null);
    if (start < 0) {
      return { dates: [] as string[], values: [] as (number | null)[] };
    }
    return {
      dates: dates.slice(start),
      values: column.slice(start).map((value) => (value == null ? null : value)),
    };
  });
  const longest = spans.reduce(
    (best, span) => (span.dates.length > best.dates.length ? span : best),
    { dates: [] as string[], values: [] as (number | null)[] },
  );
  const labels = Array.from({ length: longest.dates.length }, (_, index) => String(index + 1));
  return {
    labels,
    columns: spans.map((span) => labels.map((_, index) => (index < span.values.length ? span.values[index] : null))),
    dateToDay: new Map(longest.dates.map((date, index) => [date, String(index + 1)])),
    dayToDate: new Map(longest.dates.map((date, index) => [String(index + 1), date])),
  };
}

function categoryDates(option: ChartOption) {
  const axis = option.xAxis;
  if (!axis || Array.isArray(axis) || !Array.isArray(axis.data)) {
    return null;
  }
  return axis.data.map((value) => String(value));
}

function hasEvents(option: ChartOption) {
  return (option.series ?? []).some((series) => series.name === "Events");
}

/** Mobile ChartCard uses an HTML event strip instead of markLine labels on the plot. */
function stripEventMarks(option: ChartOption): ChartOption {
  return {
    ...option,
    series: (option.series ?? [])
      .filter((item) => item.name !== "Events")
      .map((item) => ({ ...item, markLine: undefined })),
  };
}

function remapMarks<T extends { data?: MarkItem[] }>(mark: T | undefined, dateToDay: Map<string, string>) {
  if (!mark || !Array.isArray(mark.data)) {
    return mark;
  }
  return {
    ...mark,
    data: mark.data.flatMap((item) => {
      const day = dateToDay.get(String(item.xAxis));
      if (!day) {
        return [];
      }
      return [{ ...item, xAxis: day }];
    }),
  };
}

function rewriteTooltipItem(item: unknown, day: string, date: string) {
  if (!item || typeof item !== "object") {
    return item;
  }
  const record = item as { axisValue?: unknown; name?: unknown };
  return {
    ...record,
    axisValue: record.axisValue === day ? date : record.axisValue,
    name: record.name === day ? date : record.name,
  };
}

function wrapTooltip(tooltip: ChartOption["tooltip"], dayToDate: Map<string, string>, preserveSnapshotDate = false) {
  if (!tooltip || typeof tooltip.formatter !== "function") {
    return tooltip;
  }
  const original = tooltip.formatter;
  return {
    ...tooltip,
    formatter: (params: unknown) => {
      const list = Array.isArray(params) ? params : [params];
      const first = list[0] as { axisValue?: unknown; name?: unknown } | undefined;
      const day = String(first?.axisValue ?? first?.name ?? "");
      const date = dayToDate.get(day);
      if (!date) {
        return original(params);
      }
      const rewritten = Array.isArray(params)
        ? params.map((item) => rewriteTooltipItem(item, day, date))
        : rewriteTooltipItem(params, day, date);
      const html = original(rewritten);
      if (typeof html !== "string") {
        return html;
      }
      if (preserveSnapshotDate) return `<div>Day ${day}</div>${html}`;
      return html.replaceAll(formatChartAxisDate(date), `Day ${day}`).replaceAll(date, `Day ${day}`);
    },
  };
}

function withEvents(option: ChartOption, events: GrowthEvent[], period?: string): ChartOption {
  const dates = categoryDates(option);
  if (!dates) {
    return option;
  }
  const overlays = eventMarkOverlays(dates, events, 1, period);
  if (overlays.groups.length === 0) {
    return option;
  }
  const grid = option.grid;
  const top = typeof grid?.top === "number" ? Math.max(grid.top, 64) : grid?.top;
  return {
    ...option,
    grid: { ...grid, top },
    series: [
      ...(option.series ?? []),
      {
        name: "Events",
        type: "line",
        data: dates.map(() => null),
        showSymbol: false,
        lineStyle: { opacity: 0, width: 0 },
        tooltip: { show: false },
        markLine: overlays.markLine,
      },
    ],
  };
}

function toTimeline(option: ChartOption): ChartOption {
  const dates = categoryDates(option);
  if (!dates || dates.length === 0 || dates.some((date) => !ISO_DATE.test(date))) {
    return option;
  }
  const series = option.series ?? [];
  const columns = series.map((item) =>
    Array.isArray(item.data) ? (item.data as (number | null)[]) : dates.map(() => null),
  );
  const framed = option.snapshotHistory ? {
    labels: dates.map((_, index) => String(index + 1)), columns,
    dateToDay: new Map(dates.map((date, index) => [date, String(index + 1)])),
    dayToDate: new Map(dates.map((date, index) => [String(index + 1), date])),
  } : timelineColumns(dates, columns);
  if (framed.labels.length === 0) {
    return option;
  }
  const axis = option.xAxis as { data?: unknown; axisLabel?: Record<string, unknown> };
  return {
    ...option,
    eventAxisDates: framed.labels.map(day => framed.dayToDate.get(day)!),
    tooltip: wrapTooltip(option.tooltip, framed.dayToDate, option.snapshotHistory),
    xAxis: {
      ...axis,
      data: framed.labels,
      axisLabel: {
        ...axis.axisLabel,
        formatter: (value: string) => `Day ${value}`,
      },
    },
    series: series.map((item, index) => ({
      ...item,
      data: framed.columns[index],
      markLine: remapMarks(item.markLine, framed.dateToDay),
      markPoint: remapMarks(item.markPoint, framed.dateToDay),
    })),
  };
}

/** Missing snapshots are metadata, never numeric zero or a fake rank. */
export function withCollectionWarning(source: object, today = new Date().toISOString().slice(0, 10), extend = false): ChartOption {
  const option = source as ChartOption;
  if (option.series?.some(item => item.name === "Collection warning")) return option;
  const dates = categoryDates(option);
  if (!dates?.length) return option;
  const yesterday = new Date(`${today}T00:00:00Z`);
  yesterday.setUTCDate(yesterday.getUTCDate() - 1);
  const date = yesterday.toISOString().slice(0, 10);
  const observed = option.collectionDates ?? [];
  const absentSeries = option.collectionSeries?.filter(item => item.dates.some(day => day < date) && !item.dates.includes(date)).map(item => item.name) ?? [];
  const partial = option.partialCollectionDates?.includes(date) || absentSeries.length > 0;
  const absent = option.missedCollectionDates?.includes(date) || (observed.some(day => day < date) && !observed.includes(date));
  const warning = option.collectionWarning ?? (partial ? { date, text: `Partial data for ${date}: ${absentSeries.length ? absentSeries.join(", ") : "one or more repositories"} — no stored snapshot.` } : absent ? { date, text: `No stored snapshot for ${date} (T-1).` } : null);
  if (!warning) return option;
  // Finite windows already include yesterday. All-history axes may end at the last snapshot.
  const axisDates = option.eventAxisDates ?? dates;
  let slot = axisDates.indexOf(warning.date);
  if (slot < 0 && (extend || option.collectionWarning) && /^\d{4}-/.test(dates[0]) && warning.date > dates[dates.length - 1]) {
    dates.push(warning.date); slot = dates.length - 1;
  }
  if (slot < 0) return option;
  const values = (option.series ?? []).filter(item => item.type === "line" && item.name !== "Events").flatMap(item => Array.isArray(item.data) ? item.data.filter((value): value is number => typeof value === "number") : []);
  const inverse = option.yAxis && !Array.isArray(option.yAxis) && option.yAxis.inverse;
  const height = values.length ? (inverse ? Math.min(...values) : Math.max(...values)) : (inverse ? 1 : 0);
  return { ...option, xAxis: { ...option.xAxis, data: dates }, eventAxisDates: dates.every(day => /^\d{4}-/.test(day)) ? dates : option.eventAxisDates,
    series: [...(option.series ?? []).map(item => ({ ...item, data: Array.isArray(item.data) ? [...item.data, ...Array(Math.max(0, dates.length - item.data.length)).fill(null)] : item.data })), {
      name: "Collection warning", type: "scatter", data: [],
      markPoint: { symbol: "triangle", symbolSize: 22, symbolOffset: [0, -14],
        itemStyle: { color: "#fef3c7", borderColor: "#d97706", borderWidth: 1 },
        label: { show: true, formatter: "!", color: "#92400e", fontSize: 13 },
        tooltip: { trigger: "item", formatter: () => warning.text },
        data: [{ xAxis: dates[slot], yAxis: height }],
      },
    } as Series],
  };
}

/** Confine both normal and marker tooltips; explicit wrapping beats ECharts' nowrap default. */
function safeTooltip(tooltip: unknown, mobile: boolean): Record<string, unknown> {
  return { ...(tooltip as Record<string, unknown>), confine: true, appendToBody: false,
    ...(mobile ? { triggerOn: "click" } : {}),
    extraCssText: "max-width: min(360px, calc(100vw - 48px)); min-width: 0; white-space: normal; overflow-wrap: anywhere; box-sizing: border-box;",
  };
}

/** Calendar dates stay as they are. Timeline and event markers are applied on top of a finished option. */
export function presentChartOption<T>(
  option: T,
  mode: { axis: ChartAxis; events: GrowthEvent[]; period?: Period; mobile?: boolean },
): T {
  if (!option || typeof option !== "object") {
    return option;
  }
  const input = option as ChartOption;
  const windowStart = mode.period ? periodDates(mode.period, new Date().toISOString().slice(0, 10))?.[0] : null;
  const extend = mode.period === "all" || Boolean(windowStart && (input.eventAxisDates ?? categoryDates(input) ?? []).some(date => date >= windowStart));
  let next = withCollectionWarning(input, undefined, extend);
  if (next.tooltip || next.series?.some(item => item.type === "line" || item.markPoint)) next = { ...next, ...(next.tooltip ? { tooltip: safeTooltip(next.tooltip, Boolean(mode.mobile)) } : {}), series: next.series?.map(item => ({
    ...item,
    ...(item.type === "line" && item.name !== "Events" ? { showSymbol: true, showAllSymbol: true } : {}),
    ...("tooltip" in item ? { tooltip: safeTooltip(item.tooltip, Boolean(mode.mobile)) } : {}),
    ...(item.markPoint ? { markPoint: { ...item.markPoint, tooltip: safeTooltip((item.markPoint as { tooltip?: unknown }).tooltip, Boolean(mode.mobile)) } } : {}),
  })) };
  if (mode.mobile) {
    next = stripEventMarks(next);
    // Reserve space for labels dynamically instead of the desktop legend and gutters.
    next = { ...next, grid: { ...next.grid, left: 4, right: next.series?.some(item => item.name === "Collection warning") ? 20 : 4, top: next.series?.some(item => item.name === "Collection warning") ? 40 : next.yAxis && !Array.isArray(next.yAxis) && next.yAxis.name ? 32 : 12, bottom: 8, containLabel: true } };
  } else if (mode.events.length > 0 && !hasEvents(next)) {
    next = withEvents(next, mode.events, mode.period);
  }
  if (mode.axis === "timeline") {
    next = toTimeline(next);
  }
  return next as T;
}
