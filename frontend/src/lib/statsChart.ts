import type { StatsHistoryPoint } from "./api";
import { partialDayTooltip } from "./trafficGap";
import { formatChartAxisDate, formatNumber } from "./utils";

type StatsLeader = { fullName: string; value: number };

function leadersFor(point: StatsHistoryPoint | undefined, seriesName: string) {
  if (!point) {
    return undefined;
  }
  if (seriesName === "Stars") {
    return point.starsLeaders;
  }
  if (seriesName === "Forks") {
    return point.forksLeaders;
  }
  if (seriesName === "Watchers") {
    return point.watchersLeaders;
  }
  if (seriesName === "Contributors") {
    return point.contributorsLeaders;
  }
  return undefined;
}

function shareLines(total: number | null | undefined, leaders: StatsLeader[] | null | undefined) {
  if (total == null || total <= 0 || leaders == null || leaders.length === 0) {
    return "";
  }
  const rows = leaders.filter((leader) => leader.value > 0).slice(0, 3);
  if (rows.length === 0) {
    return "";
  }
  return rows.map((leader) => `<div>${leader.fullName} - ${formatNumber(leader.value)}</div>`).join("");
}

type TooltipItem = { axisValue?: string; name?: string; seriesName: string; data: number | { value?: number | null } | null };

function pointValue(data: TooltipItem["data"]) {
  if (data == null) {
    return null;
  }
  if (typeof data === "number") {
    return data;
  }
  return data.value ?? null;
}

function tooltipItems(raw: unknown): TooltipItem[] | null {
  if (Array.isArray(raw)) {
    return raw.length === 0 ? null : raw;
  }
  if (raw && typeof raw === "object" && "seriesName" in raw) {
    return [raw as TooltipItem];
  }
  return null;
}

export function statsChartOption(points: StatsHistoryPoint[]) {
  const byDate = new Map(points.map((point) => [point.date, point]));
  const dates = points.map((point) => point.date);
  return {
    animation: false,
    tooltip: {
      trigger: "item" as const,
      triggerOn: "click" as const,
      formatter: (raw: unknown) => {
        const items = tooltipItems(raw);
        if (!items) {
          return "";
        }
        const dateKey = String(items[0].axisValue ?? items[0].name ?? "");
        if (!dateKey) {
          return "";
        }
        const point = byDate.get(dateKey);
        const missing = point?.missingRepositories ?? 0;
        const partial = missing > 0 && point?.stars != null
          ? `<div>${partialDayTooltip(missing, dateKey, "stats")}</div>`
          : "";
        const rows = items
          .map((item) => {
            const value = pointValue(item.data);
            const shown = value === null || value === undefined ? "—" : formatNumber(value);
            const share = shareLines(value, leadersFor(point, item.seriesName));
            return `<div>${item.seriesName} - ${shown}</div>${share}`;
          })
          .join("");
        return `<div><div>${formatChartAxisDate(dateKey)}</div>${partial}${rows}</div>`;
      },
    },
    axisPointer: { show: false },
    legend: { top: 0, data: ["Stars", "Forks", "Watchers", "Contributors"] },
    grid: {
      left: 48,
      right: 24,
      top: 48,
      bottom: 40,
      containLabel: false,
    },
    xAxis: {
      type: "category" as const,
      data: dates,
      boundaryGap: dates.length < 2,
      axisLabel: {
        hideOverlap: true,
        showMinLabel: true,
        showMaxLabel: true,
        alignMinLabel: "left" as const,
        alignMaxLabel: "right" as const,
        formatter: (value: string) => formatChartAxisDate(String(value)),
      },
    },
    yAxis: { type: "value" as const },
    series: [
      {
        name: "Stars",
        type: "line" as const,
        showSymbol: true,
        symbolSize: 8,
        connectNulls: false,
        data: points.map((point) => point.stars),
      },
      {
        name: "Forks",
        type: "line" as const,
        showSymbol: true,
        symbolSize: 8,
        connectNulls: false,
        data: points.map((point) => point.forks),
      },
      {
        name: "Watchers",
        type: "line" as const,
        showSymbol: true,
        symbolSize: 8,
        connectNulls: false,
        data: points.map((point) => point.watchers),
      },
      {
        name: "Contributors",
        type: "line" as const,
        showSymbol: true,
        symbolSize: 8,
        connectNulls: false,
        data: points.map((point) => point.contributors),
      },
    ],
  };
}
