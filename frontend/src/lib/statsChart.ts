import type { StatsHistoryPoint } from "./api";
import { formatChartAxisDate, formatNumber } from "./utils";

export function statsChartOption(points: StatsHistoryPoint[]) {
  const dates = points.map((point) => point.date);
  return {
    animation: false,
    tooltip: {
      trigger: "axis",
      formatter: (params: { axisValue: string; seriesName: string; data?: number | null }[]) => {
        if (!params.length) {
          return "";
        }
        const date = formatChartAxisDate(params[0].axisValue);
        const rows = params
          .map((item) => {
            const value = item.data === null || item.data === undefined ? "—" : formatNumber(item.data);
            return `<div style="display:flex;justify-content:space-between;gap:24px"><span>${item.seriesName}</span><span>${value}</span></div>`;
          })
          .join("");
        return `<div style="min-width:160px"><div style="margin-bottom:6px">${date}</div>${rows}</div>`;
      },
    },
    legend: { data: ["Stars", "Forks", "Watchers", "Contributors"] },
    grid: {
      left: 48,
      right: 24,
      top: 48,
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
        name: "Stars",
        type: "line",
        showSymbol: true,
        symbolSize: 8,
        connectNulls: false,
        data: points.map((point) => point.stars),
      },
      {
        name: "Forks",
        type: "line",
        showSymbol: true,
        symbolSize: 8,
        connectNulls: false,
        data: points.map((point) => point.forks),
      },
      {
        name: "Watchers",
        type: "line",
        showSymbol: true,
        symbolSize: 8,
        connectNulls: false,
        data: points.map((point) => point.watchers),
      },
      {
        name: "Contributors",
        type: "line",
        showSymbol: true,
        symbolSize: 8,
        connectNulls: false,
        data: points.map((point) => point.contributors),
      },
    ],
  };
}
