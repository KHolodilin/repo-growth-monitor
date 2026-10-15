import { formatChartAxisDate, formatNumber } from "./utils";

type TrafficLeader = { fullName: string; value: number };

type DashboardTrafficPoint = {
  date: string;
  views: number | null;
  visitors: number | null;
  clones: number | null;
  viewsLeaders?: TrafficLeader[] | null;
  visitorsLeaders?: TrafficLeader[] | null;
  clonesLeaders?: TrafficLeader[] | null;
};

function leadersFor(point: DashboardTrafficPoint | undefined, seriesName: string) {
  if (!point) {
    return undefined;
  }
  if (seriesName === "Views") {
    return point.viewsLeaders;
  }
  if (seriesName === "Visitors") {
    return point.visitorsLeaders;
  }
  if (seriesName === "Clones") {
    return point.clonesLeaders;
  }
  return undefined;
}

function shareLines(total: number | null | undefined, leaders: TrafficLeader[] | null | undefined) {
  if (total == null || total <= 0 || leaders == null || leaders.length === 0) {
    return "";
  }
  const rows = leaders.filter((leader) => leader.value > 0).slice(0, 3);
  if (rows.length === 0) {
    return "";
  }
  return rows.map((leader) => `<div>${leader.fullName} - ${formatNumber(leader.value)}</div>`).join("");
}

type TooltipItem = { axisValue?: string; name?: string; seriesName: string; data: number | null };

function tooltipItems(raw: unknown): TooltipItem[] | null {
  if (Array.isArray(raw)) {
    return raw.length === 0 ? null : raw;
  }
  if (raw && typeof raw === "object" && "seriesName" in raw) {
    return [raw as TooltipItem];
  }
  return null;
}

export function dashboardTrafficOption(traffic: DashboardTrafficPoint[]) {
  const byDate = new Map(traffic.map((point) => [point.date, point]));
  return {
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
        const rows = items
          .map((item) => {
            const value = item.data === null || item.data === undefined ? "—" : formatNumber(item.data);
            const share = shareLines(item.data, leadersFor(point, item.seriesName));
            return `<div>${item.seriesName} - ${value}</div>${share}`;
          })
          .join("");
        return `<div><div>${formatChartAxisDate(dateKey)}</div>${rows}</div>`;
      },
    },
    legend: { top: 0, data: ["Views", "Visitors", "Clones"] },
    grid: { left: 48, right: 72, top: 40, bottom: 40, containLabel: false },
    xAxis: {
      type: "category" as const,
      data: traffic.map((point) => point.date),
      boundaryGap: traffic.length < 2,
      axisLabel: {
        hideOverlap: true,
        showMinLabel: true,
        showMaxLabel: true,
        alignMinLabel: "left" as const,
        alignMaxLabel: "right" as const,
        formatter: (value: string) => formatChartAxisDate(String(value)),
      },
    },
    yAxis: { type: "value" as const, name: "Metric value" },
    series: [
      { name: "Views", type: "line" as const, showSymbol: true, symbolSize: 8, connectNulls: false, data: traffic.map((point) => point.views ?? null) },
      { name: "Visitors", type: "line" as const, showSymbol: true, symbolSize: 8, connectNulls: false, data: traffic.map((point) => point.visitors ?? null) },
      { name: "Clones", type: "line" as const, showSymbol: true, symbolSize: 8, connectNulls: false, data: traffic.map((point) => point.clones ?? null) },
    ],
  };
}
