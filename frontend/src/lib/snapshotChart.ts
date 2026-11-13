import type { SnapshotHistory } from "./api";
import { OTHER_KEY, chartValue } from "./snapshotHistory";
import type { SnapshotMetric } from "./snapshotChartPrefs";
import { lineStyle, prepareSeriesStyles, seriesMarkerHtml } from "./seriesStyle";
import { formatChartAxisDate, formatNumber } from "./utils";
export function snapshotSeriesKey(history: SnapshotHistory, name: string) {
  return name === OTHER_KEY ? name : `${history.kind === "REFERRERS" ? "source" : "path"}:${String(history.repositoryId).startsWith("public:") ? `${history.repositoryId}:` : ""}${name}`;
}
export function snapshotChartOption({ history, metric, selected, displayLabel = (key: string) => key }: { displayLabel?: (key: string) => string; history: SnapshotHistory; metric: SnapshotMetric; selected: string[] }) {
  const dates = history.dates;
  const unit = metric === "VISITORS" ? "Visitors" : "Views";
  const byKey = new Map(history.rows.map(row => [row.key, new Map(row.cells.map(cell => [cell.date, cell]))]));
  const names = [...selected, OTHER_KEY];
  prepareSeriesStyles([...history.rows.map(row => row.key), OTHER_KEY].map(name => snapshotSeriesKey(history, name)));
  function observation(name: string, date: string) {
    if (name !== OTHER_KEY) {
      const cell = byKey.get(name)?.get(date);
      return { value: cell ? chartValue(cell, metric) : null, missing: 0 };
    }
    let sum: number | null = null;
    let missing = 0;
    for (const row of history.rows) {
      if (selected.includes(row.key)) continue;
      const cell = byKey.get(row.key)!.get(date);
      const value = cell ? chartValue(cell, metric) : null;
      if (value === null) missing++;
      else sum = (sum ?? 0) + value;
    }
    return { value: sum, missing };
  }
  return {
    snapshotHistory: true,
    collectionDates: history.dates,
    exportDates: dates,
    exportExtraColumns: [{ name: "Other observation completeness", data: dates.map(date => {
      const point = observation(OTHER_KEY, date);
      return point.missing > 0 ? `partial: ${point.missing} unavailable` : point.value === null ? "unavailable / no residual sources" : "observed";
    }) }],
    exportLabels: Object.fromEntries(names.map(name => [name, `${displayLabel(name)} - ${unit} (snapshot rolling window)`])),
    tooltip: { trigger: "axis" as const, formatter: (params: { axisValue: string; seriesName: string }[]) => {
      if (!Array.isArray(params) || params.length === 0) return "";
      const date = params[0].axisValue;
      const rows = params.map(item => {
        const point = observation(item.seriesName, date);
        const value = point.value === null ? "— (No observation)" : formatNumber(point.value);
        const partial = point.missing > 0 ? ` (partial: ${point.missing} sources/paths unavailable)` : "";
        return `<div>${seriesMarkerHtml(snapshotSeriesKey(history, item.seriesName))} ${escapeHtml(displayLabel(item.seriesName))}: ${value} ${unit}${partial}</div>`;
      }).join("");
      return `<div>Snapshot: ${date}</div>${rows}<div>GitHub rolling-window values; not traffic for this calendar day.</div>`;
    } },
    grid: { left: 48, right: 36, top: 24, bottom: 40, containLabel: false },
    xAxis: { type: "category", data: dates, boundaryGap: dates.length < 2, axisLabel: { hideOverlap: true, showMinLabel: true, showMaxLabel: true, alignMinLabel: "left", alignMaxLabel: "right", formatter: (value: string) => formatChartAxisDate(value) } },
    yAxis: { type: "value", name: unit },
    series: names.map(name => ({ name, type: "line", showSymbol: true, symbolSize: 8, connectNulls: true, ...lineStyle(snapshotSeriesKey(history, name)), data: dates.map(date => observation(name, date).value) })),
  };
}

function escapeHtml(value: string) { return value.replace(/[&<>"']/g, char => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[char]!)); }
