import { calendarDates, formatChartAxisDate } from "./utils";

const COLORS = ["#5470c6", "#91cc75", "#fac858", "#ee6666", "#73c0de", "#3ba272", "#fc8452", "#9a60b4"];
const MISSED_COLOR = "#9ca3af";

export type RankHistoryPoint = { date: string; position: number | null };

export type RankHistorySeries = {
  name: string;
  points: RankHistoryPoint[];
  limit: number;
  /** Days the planner never ran. GitHub Search cannot be replayed, so they stay empty forever. */
  missedDates?: string[];
  highlighted?: boolean;
  dimmed?: boolean;
};

export function rankTooltipText(point: RankHistoryPoint | undefined, limit: number, missed = false) {
  if (missed) {
    return "Not collected";
  }
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
            const missed = item.missedDates?.includes(date) ?? false;
            return `${item.name}: ${rankTooltipText(point, item.limit, missed)}`;
          })
          .join("<br/>");
        return `<div>${formatChartAxisDate(date)}<br/>${rows}</div>`;
      },
    },
    legend: legend ? { type: "scroll", data: series.map((item) => item.name) } : undefined,
    grid: { left: 48, right: 72, top: legend ? 48 : 24, bottom: 40, containLabel: false },
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
      const missed = new Set(item.missedDates ?? []);
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
        // A day nobody collected is not the same as a day the repository fell out of the results,
        // so it gets its own grey marker instead of an unexplained break in the line.
        {
          name: item.name,
          type: "scatter",
          symbol: "circle",
          symbolSize: 7,
          tooltip: { show: false },
          itemStyle: { color: MISSED_COLOR, opacity: item.dimmed ? 0.25 : 0.8 },
          data: dates.map((date) => (missed.has(date) ? maxLimit + 1 : null)),
        },
      ];
    }),
  };
}

export function datesFromHistory(points: RankHistoryPoint[][], extraDates: string[] = []) {
  return calendarDates([...points.flatMap((list) => list.map((point) => point.date)), ...extraDates]);
}

/** Old gaps are history; a gap in the last week means the collection is broken right now. */
export function recentMissedDates(missedDates: string[] | undefined, days = 7) {
  if (!missedDates || missedDates.length === 0) {
    return [];
  }
  const cutoff = new Date();
  cutoff.setUTCDate(cutoff.getUTCDate() - days);
  const from = cutoff.toISOString().slice(0, 10);
  return missedDates.filter((date) => date >= from);
}
