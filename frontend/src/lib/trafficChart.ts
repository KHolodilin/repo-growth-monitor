import type { GrowthEvent, TrafficNotice, TrafficPoint } from "./api";
import { nextIsoDay, repositoryGapText, trafficWarningDate } from "./trafficGap";
import { formatChartAxisDate, formatNumber } from "./utils";
import { eventMarkerLabel, eventTypeLabel, eventUtcDate, groupEventsByUtcDate } from "./growthEvents";

/** One text row above the line. A label that does not fit is replaced by the event count. */
const LABEL_DISTANCE = 18;
const LABEL_HEADROOM = 64;
/** Width of one character at the label font size, and the gap kept between two labels. */
const LABEL_CHAR_WIDTH = 8;
const LABEL_GAP = 12;
/**
 * Narrower than the card so a long label such as "External pull request opened" is treated as
 * overlapping the next days on a 30 day axis.
 */
const ASSUMED_PLOT_WIDTH = 640;

/**
 * Every marker is labelled by its own day, on a single line. A label that would cross the one to
 * its left is replaced by the event count, which is only a few digits wide.
 */
function labelTexts(dates: string[], groups: { date: string; text: string; countText: string }[]) {
  const slot = ASSUMED_PLOT_WIDTH / Math.max(1, dates.length - 1);
  const textByDate = new Map<string, string>();
  let takenUntil = Number.NEGATIVE_INFINITY;
  const labels = groups
    .map((group) => ({
      date: group.date,
      center: dates.indexOf(group.date) * slot,
      text: group.text,
      textWidth: group.text.length * LABEL_CHAR_WIDTH,
      countText: group.countText,
      countWidth: group.countText.length * LABEL_CHAR_WIDTH,
    }))
    .sort((left, right) => left.center - right.center);
  for (const label of labels) {
    const textStart = label.center - label.textWidth / 2;
    if (takenUntil <= textStart) {
      textByDate.set(label.date, label.text);
      takenUntil = label.center + label.textWidth / 2 + LABEL_GAP;
      continue;
    }
    textByDate.set(label.date, label.countText);
    takenUntil = Math.max(takenUntil, label.center + label.countWidth / 2 + LABEL_GAP);
  }
  return textByDate;
}

/** 90 days, a year, and all write the event count. Shorter ranges keep the type text. */
function eventMarkerKind(period?: string) {
  if (period === "90d" || period === "1y" || period === "all") {
    return "count" as const;
  }
  return "text" as const;
}

export function eventMarkOverlays(
  dates: string[],
  events: GrowthEvent[],
  peak = 1,
  period?: string,
  dateOf: (event: GrowthEvent) => string = eventUtcDate,
) {
  const kind = eventMarkerKind(period);
  const onAxis = new Set(dates);
  const groups = groupEventsByUtcDate(events, dateOf).filter((group) => onAxis.has(group.date));
  const labels = groups.map((group) => {
    const countText = String(group.events.length);
    return {
      date: group.date,
      text: kind === "count" ? countText : eventMarkerLabel(group.events),
      countText,
      events: group.events,
    };
  });
  const visibleText = kind === "count"
    ? new Map(labels.map((label) => [label.date, label.text]))
    : labelTexts(dates, labels);
  const tooltip = {
    formatter: (params: { data?: { events?: GrowthEvent[] } }) => {
      const items = params.data?.events ?? [];
      if (items.length === 0) {
        return "";
      }
      const rows = items
        .map((item) => `<div>${item.title}<div style="opacity:.7">${eventTypeLabel(item.type)}</div></div>`)
        .join("");
      return `<div style="min-width:180px">${rows}</div>`;
    },
  };
  return {
    groups,
    kind,
    markLine: {
      symbol: "none",
      silent: false,
      animation: false,
      label: {
        show: true,
        color: "#0969da",
        fontSize: 11,
        position: "end" as const,
        distance: LABEL_DISTANCE,
        formatter: "{b}",
      },
      lineStyle: { type: "dashed" as const, color: "#0969da", width: 1.5 },
      tooltip,
      data: labels.map((label) => ({
        xAxis: label.date,
        name: visibleText.get(label.date)!,
        label: { show: true, position: "end" as const, distance: LABEL_DISTANCE },
        events: label.events,
      })),
    },
    markPoint: {
      symbol: "pin",
      symbolSize: 42,
      silent: false,
      itemStyle: { color: "#0969da" },
      label: { color: "#fff", fontSize: 11, formatter: (params: { data?: { events?: GrowthEvent[]; countAsText?: boolean } }) => {
        const count = params.data?.events?.length ?? 0;
        if (kind === "count" || params.data?.countAsText || count <= 1) {
          return "";
        }
        return String(count);
      } },
      tooltip,
      data: groups.map((group) => ({
        xAxis: group.date,
        yAxis: peak,
        name: group.events[0]?.title ?? "Events",
        events: group.events,
        countAsText: kind === "count" || visibleText.get(group.date) === String(group.events.length),
      })),
    },
  };
}

export function trafficAxisDates(points: { date: string }[], events: GrowthEvent[], marker?: string | null) {
  const trafficDates = points.map((point) => point.date).sort();
  if (trafficDates.length === 0) {
    const dates = chartDatesWithEvents([], events);
    if (marker && !dates.includes(marker)) {
      dates.push(marker);
    }
    return dates.sort();
  }
  let end = trafficDates[trafficDates.length - 1];
  if (marker && marker > end) {
    end = marker;
  }
  const filled: string[] = [];
  for (let day = trafficDates[0]; day <= end; day = nextIsoDay(day)) {
    filled.push(day);
  }
  const extras = chartDatesWithEvents(points, events).filter((date) => date < trafficDates[0] || date > end);
  return [...filled, ...extras].sort();
}

export function chartDatesWithEvents(points: TrafficPoint[], events: GrowthEvent[]) {
  const dates = new Set(points.map((point) => point.date));
  for (const event of events) {
    dates.add(eventUtcDate(event));
  }
  return [...dates].sort();
}

export function trafficChartOption(
  points: TrafficPoint[],
  events: GrowthEvent[],
  period?: string,
  gap?: TrafficNotice | null,
) {
  const marker = trafficWarningDate(points, gap);
  const dates = trafficAxisDates(points, events, marker);
  const gapText = gap && marker ? repositoryGapText(gap) : "";
  const byDate = new Map(points.map((point) => [point.date, point]));
  const peak = Math.max(
    1,
    ...points.flatMap((point) => [point.views, point.uniqueVisitors, point.uniqueCloners].filter((value): value is number => value != null)),
  );
  const { groups, markLine } = eventMarkOverlays(dates, events, peak, period);

  return {
    tooltip: {
      trigger: "axis" as const,
      formatter: (params: { axisValue: string; seriesName: string; data: number | null }[]) => {
        if (!Array.isArray(params) || params.length === 0) {
          return "";
        }
        if (marker && params[0].axisValue === marker && gapText) {
          return gapText;
        }
        const date = formatChartAxisDate(params[0].axisValue);
        const rows = params
          .filter((item) => item.seriesName !== "Events")
          .map((item) => {
            const value = item.data === null || item.data === undefined ? "—" : formatNumber(item.data);
            return `<div style="display:flex;justify-content:space-between;gap:24px"><span>${item.seriesName}</span><span>${value}</span></div>`;
          })
          .join("");
        const dayEvents = groups.find((group) => group.date === params[0].axisValue)?.events ?? [];
        const eventRows =
          dayEvents.length === 0
            ? ""
            : `<div style="margin-top:8px;padding-top:8px;border-top:1px solid rgba(0,0,0,.08)">${dayEvents
                .map((item) => `<div>${item.title}</div>`)
                .join("")}</div>`;
        return `<div style="min-width:160px"><div style="margin-bottom:6px">${date}</div>${rows}${eventRows}</div>`;
      },
    },
    legend: { top: 0, data: ["Views", "Visitors", "Unique Cloners"] },
    grid: {
      left: 48,
      right: 72,
      top: groups.length > 0 ? LABEL_HEADROOM : 48,
      bottom: 40,
      containLabel: false,
    },
    xAxis: {
      type: "category",
      data: dates,
      boundaryGap: dates.length < 2,
      axisLabel: {
        hideOverlap: true,
        showMinLabel: true,
        showMaxLabel: true,
        alignMinLabel: "left",
        alignMaxLabel: "right",
        formatter: (value: string) => formatChartAxisDate(String(value)),
      },
    },
    yAxis: { type: "value" },
    series: [
      {
        name: "Views",
        type: "line",
        showSymbol: true,
        symbolSize: 8,
        connectNulls: true,
        data: dates.map((date) => byDate.get(date)?.views ?? null),
        markPoint: marker
          ? {
              symbol: "circle",
              symbolSize: 22,
              itemStyle: { color: "#fef3c7", borderColor: "#d97706", borderWidth: 1 },
              label: { show: true, formatter: "⚠", color: "#b45309", fontSize: 14 },
              tooltip: { formatter: () => gapText },
              data: [{ xAxis: marker, yAxis: 0 }],
            }
          : undefined,
      },
      {
        name: "Visitors",
        type: "line",
        showSymbol: true,
        symbolSize: 8,
        connectNulls: true,
        data: dates.map((date) => byDate.get(date)?.uniqueVisitors ?? null),
      },
      {
        name: "Unique Cloners",
        type: "line",
        showSymbol: true,
        symbolSize: 8,
        connectNulls: true,
        data: dates.map((date) => byDate.get(date)?.uniqueCloners ?? null),
      },
      {
        name: "Events",
        type: "line",
        data: dates.map(() => null),
        showSymbol: false,
        lineStyle: { opacity: 0, width: 0 },
        tooltip: { show: false },
        markLine,
      },
    ],
  };
}

export function markLineEvents(params: { componentType?: string; data?: { events?: GrowthEvent[] } }): GrowthEvent[] {
  if (params.componentType !== "markLine" && params.componentType !== "markPoint") {
    return [];
  }
  return params.data?.events ?? [];
}
