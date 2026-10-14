import { formatChartAxisDate, formatNumber } from "./utils";

type DashboardTrafficPoint = {
  date: string;
  views: number | null;
  visitors: number | null;
  clones: number | null;
};

export function dashboardTrafficOption(traffic: DashboardTrafficPoint[]) {
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
        return `<div style="min-width:140px"><div style="margin-bottom:6px">${date}</div>${rows}</div>`;
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
