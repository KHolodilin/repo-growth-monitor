import { calendarDates } from "./utils";

const COLORS = ["#5470c6", "#91cc75", "#fac858", "#ee6666", "#73c0de", "#3ba272", "#fc8452", "#9a60b4"];

export type RankHistoryPoint = { date: string; position: number | null };

export type RankHistorySeries = {
  name: string;
  points: RankHistoryPoint[];
  limit: number;
  highlighted?: boolean;
  dimmed?: boolean;
};

export function rankTooltipText(point: RankHistoryPoint | undefined, limit: number) {
  if (!point) {
    return "no data";
  }
  if (point.position == null) {
    return `Not found in Top ${limit}`;
  }
  return `#${point.position}`;
}

export function rankHistoryOption({
  dates,
  series,
  legend = false,
}: {
  dates: string[];
  series: RankHistorySeries[];
  legend?: boolean;
}) {
  const maxLimit = Math.max(50, ...series.map((item) => item.limit));
  return {
    tooltip: {
      trigger: "axis",
      formatter: (params: { axisValue: string }[]) => {
        if (!Array.isArray(params) || params.length === 0) {
          return "";
        }
        const date = params[0].axisValue;
        const rows = series
          .map((item) => {
            const point = item.points.find((entry) => entry.date === date);
            return `${item.name}: ${rankTooltipText(point, item.limit)}`;
          })
          .join("<br/>");
        return `<div>${date}<br/>${rows}</div>`;
      },
    },
    legend: legend ? { type: "scroll", data: series.map((item) => item.name) } : undefined,
    grid: { left: 24, right: 16, top: legend ? 48 : 24, bottom: 24, containLabel: true },
    xAxis: { type: "category", data: dates, boundaryGap: false },
    yAxis: {
      type: "value",
      inverse: true,
      min: 1,
      max: maxLimit + 1,
      axisLabel: {
        formatter: (value: number) => (value > maxLimit ? `>${maxLimit}` : `#${value}`),
      },
    },
    series: series.flatMap((item, index) => {
      const color = COLORS[index % COLORS.length];
      const byDate = new Map(item.points.map((point) => [point.date, point]));
      return [
        {
          name: item.name,
          type: "line",
          connectNulls: false,
          showSymbol: true,
          symbol: "circle",
          symbolSize: item.highlighted ? 8 : 6,
          lineStyle: { width: item.highlighted ? 3.5 : 2, opacity: item.dimmed ? 0.25 : 1, color },
          itemStyle: { color, opacity: item.dimmed ? 0.25 : 1 },
          data: dates.map((date) => {
            const point = byDate.get(date);
            if (!point || point.position == null) {
              return null;
            }
            return point.position;
          }),
        },
        {
          name: item.name,
          type: "scatter",
          symbol: "diamond",
          symbolSize: 10,
          tooltip: { show: false },
          itemStyle: {
            color: "#fff",
            borderColor: color,
            borderWidth: 2,
            opacity: item.dimmed ? 0.25 : 1,
          },
          data: dates.map((date) => {
            const point = byDate.get(date);
            if (!point || point.position != null) {
              return null;
            }
            return maxLimit + 1;
          }),
        },
      ];
    }),
  };
}

export function datesFromHistory(points: RankHistoryPoint[][]) {
  return calendarDates(points.flatMap((list) => list.map((point) => point.date)));
}
