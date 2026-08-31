import type { GrowthEvent, TrafficPoint } from "./api";
import { formatChartAxisDate, formatNumber } from "./utils";
import { eventTypeLabel, groupEventsByUtcDate } from "./growthEvents";

export function trafficChartOption(points: TrafficPoint[], events: GrowthEvent[]) {
  const dates = new Set(points.map((point) => point.date));
  const groups = groupEventsByUtcDate(events).filter((group) => dates.has(group.date));
  const markLine = {
    symbol: "none",
    silent: false,
    animation: false,
    label: { show: true, color: "#0969da", fontSize: 11 },
    lineStyle: { type: "dashed" as const, color: "#0969da", width: 1 },
    tooltip: {
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
    },
    data: groups.map((group) => ({
      xAxis: group.date,
      name: group.events[0]?.title ?? "Events",
      label: { formatter: group.events.length > 1 ? String(group.events.length) : "•" },
      events: group.events,
    })),
  };

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
    grid: { left: 48, right: 72, top: 40, bottom: 40, containLabel: false },
    xAxis: {
      type: "category",
      data: points.map((point) => point.date),
      boundaryGap: points.length < 2,
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
        data: points.map((point) => point.views ?? null),
        markLine,
      },
      {
        name: "Visitors",
        type: "line",
        showSymbol: true,
        symbolSize: 8,
        connectNulls: false,
        data: points.map((point) => point.uniqueVisitors ?? null),
      },
      {
        name: "Clones",
        type: "line",
        showSymbol: true,
        symbolSize: 8,
        connectNulls: false,
        data: points.map((point) => point.clones ?? null),
      },
    ],
  };
}

export function markLineEvents(params: { componentType?: string; data?: { events?: GrowthEvent[] } }): GrowthEvent[] {
  if (params.componentType !== "markLine") {
    return [];
  }
  return params.data?.events ?? [];
}
