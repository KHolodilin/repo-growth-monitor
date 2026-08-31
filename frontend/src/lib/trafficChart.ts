import type { GrowthEvent, TrafficPoint } from "./api";
import { formatChartAxisDate, formatNumber } from "./utils";
import { eventTypeLabel, eventUtcDate, groupEventsByUtcDate } from "./growthEvents";

export function eventMarkOverlays(dates: string[], events: GrowthEvent[], peak = 1) {
  const onAxis = new Set(dates);
  const groups = groupEventsByUtcDate(events).filter((group) => onAxis.has(group.date));
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
      label: { show: true, color: "#0969da", fontSize: 11, formatter: "{b}" },
      lineStyle: { type: "dashed" as const, color: "#0969da", width: 1.5 },
      tooltip,
      data: groups.map((group) => ({
        xAxis: group.date,
        name: group.events.length > 1 ? `${group.events.length} events` : group.events[0]?.title,
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
    grid: { left: 48, right: 72, top: 48, bottom: 40, containLabel: false },
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
