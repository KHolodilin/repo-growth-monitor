import { referrerLineColor } from "../components/ReferrerSourceIcon";
import type { SnapshotHistory } from "./api";
import { OTHER_KEY, chartValue } from "./snapshotHistory";
import type { SnapshotMetric } from "./snapshotChartPrefs";
import { formatChartAxisDate, formatNumber } from "./utils";

export function snapshotChartOption({
  history,
  metric,
  selected,
}: {
  history: SnapshotHistory;
  metric: SnapshotMetric;
  selected: string[];
}) {
  const dates = history.dates;
  const visitors = metric === "VISITORS";
  const unit = visitors ? "Visitors" : "Views";
  const byKey = new Map(history.rows.map((row) => [row.key, row]));
  const seriesNames = [...selected, OTHER_KEY];
  const previousByDate = new Map(dates.slice(1).map((date, index) => [date, dates[index]]));

  return {
    tooltip: {
      trigger: "axis" as const,
      formatter: (params: { axisValue: string; seriesName: string; data: number | null }[]) => {
        if (!Array.isArray(params) || params.length === 0) {
          return "";
        }
        const date = params[0].axisValue;
        const previous = previousByDate.get(date);
        const gap =
          previous && nextDay(previous) !== date
            ? `<div style="margin-bottom:6px;opacity:.7">Since previous snapshot: ${formatChartAxisDate(previous)}</div>`
            : "";
        const rows = params
          .map((item) => {
            const value = item.data === null || item.data === undefined ? "—" : formatNumber(item.data);
            return `<div style="display:flex;justify-content:space-between;gap:24px"><span>${item.seriesName}</span><span>${value} ${unit}</span></div>`;
          })
          .join("");
        return `<div style="min-width:180px"><div style="margin-bottom:6px">${formatChartAxisDate(date)}</div>${gap}${rows}</div>`;
      },
    },
    grid: { left: 48, right: 72, top: 16, bottom: 40, containLabel: false },
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
    yAxis: { type: "value", name: unit },
    series: seriesNames.map((name, index) => ({
      name,
      type: "line",
      showSymbol: true,
      symbolSize: 8,
      connectNulls: false,
      itemStyle: { color: referrerLineColor(name, index) },
      lineStyle: { color: referrerLineColor(name, index) },
      data: dates.map((_, dateIndex) => {
        if (name === OTHER_KEY) {
          let sum: number | null = null;
          for (const row of history.rows) {
            if (selected.includes(row.key)) {
              continue;
            }
            const value = chartValue(row.cells[dateIndex], metric);
            if (value !== null) {
              sum = (sum ?? 0) + value;
            }
          }
          return sum;
        }
        const cell = byKey.get(name)?.cells[dateIndex];
        return cell ? chartValue(cell, metric) : null;
      }),
    })),
  };
}

function nextDay(value: string) {
  const date = new Date(`${value}T00:00:00Z`);
  date.setUTCDate(date.getUTCDate() + 1);
  return date.toISOString().slice(0, 10);
}
