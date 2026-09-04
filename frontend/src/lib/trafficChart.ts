import type { GrowthEvent, TrafficPoint } from "./api";
import { formatChartAxisDate, formatNumber } from "./utils";
import { eventMarkerLabel, eventTypeLabel, eventUtcDate, groupEventsByUtcDate } from "./growthEvents";

const LABEL_LINES = 4;
const LABEL_LINE_HEIGHT = 15;
/** The pin of a marker sits on the peak value, so labels have to clear it. */
const LABEL_DISTANCE = 30;
/** Room above the plot for the legend and for every label line. */
const LABEL_HEADROOM = 50 + LABEL_LINES * LABEL_LINE_HEIGHT;
/** Width of one character at the label font size, and the gap kept between two labels. */
const LABEL_CHAR_WIDTH = 5.8;
const LABEL_GAP = 8;
/** Only the ratio between this and the number of dates matters when spacing labels. */
const ASSUMED_PLOT_WIDTH = 760;

/**
 * Every marker is labelled by its own day. Labels of days close together would be drawn on top of
 * each other, so a label moves one line up as long as the line below is still taken by the label to
 * its left. Label widths are estimated from the text, because "Reddit post" needs far less room
 * than "First external contributor".
 */
function labelLines(dates: string[], groups: { date: string; events: GrowthEvent[] }[]) {
  const slot = ASSUMED_PLOT_WIDTH / Math.max(1, dates.length - 1);
  const takenUntil: number[] = [];
  const lineByDate = new Map<string, number>();
  const labels = groups
    .map((group) => ({
      date: group.date,
      center: dates.indexOf(group.date) * slot,
      width: eventMarkerLabel(group.events).length * LABEL_CHAR_WIDTH,
    }))
    .sort((left, right) => left.center - right.center);
  for (const label of labels) {
    const start = label.center - label.width / 2;
    let line = 0;
    while (line < LABEL_LINES && takenUntil[line] !== undefined && takenUntil[line] > start) {
      line++;
    }
    if (line === LABEL_LINES) {
      line = freestLine(takenUntil);
    }
    takenUntil[line] = label.center + label.width / 2 + LABEL_GAP;
    lineByDate.set(label.date, line);
  }
  return lineByDate;
}

function freestLine(takenUntil: number[]) {
  let freest = 0;
  for (let line = 1; line < takenUntil.length; line++) {
    if (takenUntil[line] < takenUntil[freest]) {
      freest = line;
    }
  }
  return freest;
}

export function eventMarkOverlays(dates: string[], events: GrowthEvent[], peak = 1) {
  const onAxis = new Set(dates);
  const groups = groupEventsByUtcDate(events).filter((group) => onAxis.has(group.date));
  const lines = labelLines(dates, groups);
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
      data: groups.map((group) => ({
        xAxis: group.date,
        name: eventMarkerLabel(group.events),
        label: { offset: [0, -(lines.get(group.date) ?? 0) * LABEL_LINE_HEIGHT] },
        events: group.events,
      })),
    },
    markPoint: {
      symbol: "pin",
      symbolSize: 42,
      silent: false,
      itemStyle: { color: "#0969da" },
      label: { color: "#fff", fontSize: 11, formatter: (params: { data?: { events?: GrowthEvent[] } }) => {
        const count = params.data?.events?.length ?? 0;
        return count > 1 ? String(count) : "";
      } },
      tooltip,
      data: groups.map((group) => ({
        xAxis: group.date,
        yAxis: peak,
        name: group.events[0]?.title ?? "Events",
        events: group.events,
      })),
    },
  };
}

export function chartDatesWithEvents(points: TrafficPoint[], events: GrowthEvent[]) {
  const dates = new Set(points.map((point) => point.date));
  for (const event of events) {
    dates.add(eventUtcDate(event));
  }
  return [...dates].sort();
}

export function trafficChartOption(points: TrafficPoint[], events: GrowthEvent[]) {
  const dates = chartDatesWithEvents(points, events);
  const byDate = new Map(points.map((point) => [point.date, point]));
  const peak = Math.max(
    1,
    ...points.flatMap((point) => [point.views, point.uniqueVisitors, point.clones].filter((value): value is number => value != null)),
  );
  const { groups, markLine, markPoint } = eventMarkOverlays(dates, events, peak);

  return {
    tooltip: {
      trigger: "axis" as const,
      formatter: (params: { axisValue: string; seriesName: string; data: number | null }[]) => {
        if (!Array.isArray(params) || params.length === 0) {
          return "";
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
    legend: { data: ["Views", "Visitors", "Clones"] },
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
        connectNulls: false,
        data: dates.map((date) => byDate.get(date)?.views ?? null),
      },
      {
        name: "Visitors",
        type: "line",
        showSymbol: true,
        symbolSize: 8,
        connectNulls: false,
        data: dates.map((date) => byDate.get(date)?.uniqueVisitors ?? null),
      },
      {
        name: "Clones",
        type: "line",
        showSymbol: true,
        symbolSize: 8,
        connectNulls: false,
        data: dates.map((date) => byDate.get(date)?.clones ?? null),
      },
      {
        name: "Events",
        type: "line",
        data: dates.map(() => null),
        showSymbol: false,
        lineStyle: { opacity: 0, width: 0 },
        tooltip: { show: false },
        markLine,
        markPoint,
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
