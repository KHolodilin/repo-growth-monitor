import type { GrowthEvent } from "./api";
import { eventMarkOverlays } from "./trafficChart";
import { formatChartAxisDate } from "./utils";

export type ChartAxis = "calendar" | "timeline";

const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;

type MarkItem = { xAxis?: string | number };
type Series = {
  name?: string;
  data?: unknown;
  markLine?: { data?: MarkItem[] };
  markPoint?: { data?: MarkItem[] };
};
type ChartOption = {
  xAxis?: { data?: unknown; axisLabel?: Record<string, unknown> } | unknown[];
  series?: Series[];
  tooltip?: { formatter?: (params: unknown) => unknown };
  grid?: { top?: unknown };
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

function wrapTooltip(tooltip: ChartOption["tooltip"], dayToDate: Map<string, string>) {
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
  const framed = timelineColumns(dates, columns);
  if (framed.labels.length === 0) {
    return option;
  }
  const axis = option.xAxis as { data?: unknown; axisLabel?: Record<string, unknown> };
  return {
    ...option,
    tooltip: wrapTooltip(option.tooltip, framed.dayToDate),
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

/** Calendar dates stay as they are. Timeline and event markers are applied on top of a finished option. */
export function presentChartOption<T>(option: T, mode: { axis: ChartAxis; events: GrowthEvent[]; period?: string }): T {
  if (!option || typeof option !== "object") {
    return option;
  }
  let next = option as ChartOption;
  if (mode.events.length > 0 && !hasEvents(next)) {
    next = withEvents(next, mode.events, mode.period);
  }
  if (mode.axis === "timeline") {
    next = toTimeline(next);
  }
  return next as T;
}
